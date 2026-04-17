package com.SwapSmart.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.Instant;
import java.util.*;

import org.springframework.jdbc.core.ConnectionCallback;

@Service
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate namedDb;
    private final RestClient http;
    private final UsdaService usdaService;

    /// FILTERING KEYWORDS!!!! 
    private static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.ofEntries(
    Map.entry("dairy", List.of("milk", "cheese", "yogurt", "butter", "cream", "ice cream", "custard")),
    Map.entry("fruit", List.of("apple", "banana", "orange", "grape", "pear", "berry", "melon", "peach")),
    Map.entry("meat", List.of("beef", "chicken", "pork", "turkey", "bacon", "ham", "sausage")),
    Map.entry("snacks", List.of("chips", "cracker", "popcorn", "pretzel", "granola", "bar")),
    Map.entry("beverages", List.of("juice", "soda", "coffee", "tea", "smoothie", "drink")),
    Map.entry("vegetables", List.of("carrot", "broccoli", "lettuce", "spinach", "pepper", "onion", "tomato")),
    Map.entry("grains", List.of("rice", "pasta", "bread", "noodle", "oat", "cereal")),
    Map.entry("sweets", List.of("cake", "cookie", "candy", "chocolate", "dessert", "brownie", "donut")),
    Map.entry("seafood", List.of("fish", "salmon", "tuna", "shrimp", "crab", "lobster")),

    // diets
    Map.entry("gluten_free", List.of("gluten free", "rice", "corn", "quinoa")),
    Map.entry("dairy_free", List.of("dairy free", "almond milk", "soy milk", "oat milk")),
    Map.entry("grain_free", List.of("grain free", "almond flour", "coconut flour")),
    Map.entry("sugar_free", List.of("sugar free", "no sugar", "zero sugar", "diet"))
);

    @Value("${fatsecret.client-id}")
    private String fsClientId;
    @Value("${fatsecret.client-secret}")
    private String fsClientSecret;
    @Value("${fatsecret.token-url}")
    private String fsTokenUrl;
    @Value("${fatsecret.api-base-url}")
    private String fsApiBase;
    @Value("${openfoodfacts.api-base-url}")
    private String offApiBase;
    @Value("${openfoodfacts.user-agent}")
    private String offUserAgent;

    private String fsToken;
    private Instant fsTokenExpiry = Instant.MIN;

    public ProductService(JdbcTemplate db, UsdaService usdaService) {
        this.db = db;
        this.namedDb = new NamedParameterJdbcTemplate(db);
        this.http = RestClient.create();
        this.usdaService = usdaService;
    }

    public Map<String, Object> lookupProduct(String gtin) {
        Map<String, Object> product = findCached("fatsecret_products", "gtin", gtin);
        if (product != null) {
            log.info("FatSecret CACHE (by gtin)");
            return withSource(product, "fatsecret");
        }

        product = findCached("openfoodfacts_products", "gtin", gtin);
        if (product != null) {
            log.info("Open Food Facts CACHE");
            return withSource(product, "openfoodfacts");
        }

        product = fetchFromFatSecret(gtin);
        if (product != null) {
            log.info("FatSecret API");
            cacheFatSecretProduct(gtin, product);
            String fsCategory = str(product, "category");
            String foodId = str(product, "food_id");
            usdaService.enrichFatSecretProduct(gtin, foodId, fsCategory);
            List<Map<String, Object>> enriched = db.queryForList(
                    "SELECT product_type, usda_category FROM fatsecret_products WHERE gtin = ?", gtin);
            if (!enriched.isEmpty()) {
                product.put("product_type", enriched.getFirst().get("product_type"));
                product.put("usda_category", enriched.getFirst().get("usda_category"));
            }
            return withSource(product, "fatsecret");
        }

        product = usdaService.lookupByGtin(gtin);
        if (product != null) {
            log.info("USDA API/Cache");
            String usdaCategory = str(product, "category");
            product.put("product_type", UsdaService.deriveProductType(usdaCategory));
            return withSource(product, "usda");
        }

        product = fetchFromOpenFoodFacts(gtin);
        if (product != null) {
            log.info("Open Food Facts API");
            cacheProduct("openfoodfacts_products", gtin, product);
            return withSource(product, "openfoodfacts");
        }

        return null;
    }

    private Map<String, Object> findCached(String table, String column, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM " + table + " WHERE " + column + " = ?", value);

        if (rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    // Deduplication: RecommendationService caches products by food_id with gtin=NULL.
    // When a barcode scan finds the same product, we merge the gtin into the existing row
    // instead of creating a duplicate.
    private void cacheFatSecretProduct(String gtin, Map<String, Object> product) {
        product.put("gtin", gtin);
        String foodId = (String) product.get("food_id");

        try {
            if (foodId != null && !foodId.isBlank()) {
                Object rawCats = product.get("categories");
                Array categoriesArray = null;
                if (rawCats instanceof List) {
                    categoriesArray = toSqlTextArray((List<?>) rawCats);
                }
                int updated = db.update(
                        "UPDATE fatsecret_products SET "
                                + "gtin = ?, name = ?, brand = ?, category = ?, "
                                + "categories = COALESCE(?, categories), "
                                + "image_url = COALESCE(?, image_url), "
                                + "food_url = COALESCE(?, food_url), "
                                + "serving_size = ?, calories = ?, total_fat = ?, "
                                + "saturated_fat = ?, trans_fat = ?, cholesterol = ?, "
                                + "sodium = ?, total_carbs = ?, dietary_fiber = ?, "
                                + "total_sugars = ?, added_sugars = ?, protein = ?, "
                                + "raw_json = CAST(? AS jsonb) "
                                + "WHERE food_id = ? AND gtin IS NULL",
                        gtin, product.get("name"), product.get("brand"),
                        product.get("category"), categoriesArray,
                        product.get("image_url"),
                        product.get("food_url"),
                        product.get("serving_size"),
                        product.get("calories"), product.get("total_fat"),
                        product.get("saturated_fat"), product.get("trans_fat"),
                        product.get("cholesterol"), product.get("sodium"),
                        product.get("total_carbs"), product.get("dietary_fiber"),
                        product.get("total_sugars"), product.get("added_sugars"),
                        product.get("protein"), product.get("raw_json"),
                        foodId);

                if (updated > 0) {
                    log.info("Merged barcode {} into existing food_id {}", gtin, foodId);
                    return; // done, no duplicate created
                }
            }

            Object rawCatsInsert = product.get("categories");
            if (rawCatsInsert instanceof List) {
                product.put("categories", toSqlTextArray((List<?>) rawCatsInsert));
            }

            String cols = "gtin, food_id, name, brand, category, categories, image_url, food_url, serving_size, "
                    + "calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                    + "sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, "
                    + "protein, raw_json";
            String vals = ":gtin, :food_id, :name, :brand, :category, :categories, :image_url, :food_url, :serving_size, "
                    + ":calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                    + ":sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, "
                    + ":protein, CAST(:raw_json AS jsonb)";

            namedDb.update(
                    "INSERT INTO fatsecret_products (" + cols + ") "
                            + "VALUES (" + vals + ") "
                            + "ON CONFLICT (gtin) DO UPDATE SET "
                            + "  food_id = COALESCE(fatsecret_products.food_id, EXCLUDED.food_id), "
                            + "  categories = COALESCE(EXCLUDED.categories, fatsecret_products.categories), "
                            + "  image_url = COALESCE(EXCLUDED.image_url, fatsecret_products.image_url)",
                    product);
        } catch (Exception e) {
            log.error("FatSecret cache write failed: {}", e.getMessage());
        }
    }

    private void cacheProduct(String table, String gtin, Map<String, Object> product) {
        product.put("gtin", gtin);

        String cols = "gtin, name, brand, category, serving_size, calories, "
                + "total_fat, saturated_fat, trans_fat, cholesterol, sodium, "
                + "total_carbs, dietary_fiber, total_sugars, added_sugars, protein, "
                + "raw_json";
        String vals = ":gtin, :name, :brand, :category, :serving_size, :calories, "
                + ":total_fat, :saturated_fat, :trans_fat, :cholesterol, :sodium, "
                + ":total_carbs, :dietary_fiber, :total_sugars, :added_sugars, :protein, "
                + "CAST(:raw_json AS jsonb)";

        if (table.equals("openfoodfacts_products")) {
            cols += ", nutrition_grade";
            vals += ", :nutrition_grade";
        }

        try {
            namedDb.update(
                    "INSERT INTO " + table + " (" + cols + ") "
                            + "VALUES (" + vals + ") "
                            + "ON CONFLICT (gtin) DO NOTHING",
                    product);
        } catch (Exception e) {
            log.error("Cache write failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> fetchFromFatSecret(String gtin) {
        try {
            String token = getToken();
            if (token == null) {
                return null;
            }

            // FatSecret requires GTIN-13; pad UPC-A (12 digits) with a leading zero
            String gtin13 = String.format("%13s", gtin).replace(' ', '0');

            String body = http.get()
                    .uri(fsApiBase
                            + "/server.api?method=food.find_id_for_barcode.v2"
                            + "&barcode={gtin}"
                            + "&flag_default_serving=true"
                            + "&include_sub_categories=true"
                            + "&include_food_images=true"
                            + "&format=json",
                            gtin13)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);

            if (root.has("error")) {
                return null;
            }

            return parseFatSecret(root, body);
        } catch (Exception e) {
            log.error("FatSecret error: {}", e.getMessage());
            return null;
        }
    }

    // Package-private so RecommendationService can reuse the same token
    String getToken() {
        if (fsToken != null && Instant.now().isBefore(fsTokenExpiry)) {
            return fsToken;
        }

        try {
            String encoded = Base64.getEncoder().encodeToString(
                    (fsClientId + ":" + fsClientSecret).getBytes());

            String body = http.post()
                    .uri(fsTokenUrl)
                    .header("Authorization", "Basic " + encoded)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("grant_type=client_credentials&scope=premier%20barcode")
                    .retrieve().body(String.class);

            JsonNode tokenJson = JsonMapper.shared().readTree(body);
            fsToken = tokenJson.get("access_token").asString();

            // Refresh 60s early to avoid using an expired token
            fsTokenExpiry = Instant.now().plusSeconds(tokenJson.get("expires_in").asInt() - 60);

            return fsToken;
        } catch (Exception e) {
            log.error("FatSecret token error: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseFatSecret(JsonNode root, String rawJson) {
        JsonNode food = root.path("food");
        JsonNode servings = food.path("servings").path("serving");
        JsonNode subCats = food.path("food_sub_categories").path("food_sub_category");

        JsonNode serving = null;
        if (servings.isArray()) {
            for (JsonNode s : servings) {
                if (s.path("is_default").asInt(0) == 1) {
                    serving = s;
                    break;
                }
            }
            if (serving == null && !servings.isEmpty()) {
                serving = servings.get(0);
            }
        } else {
            serving = servings;
        }
        if (serving == null) {
            serving = JsonMapper.shared().createObjectNode();
        }

        List<String> categoriesList = new ArrayList<>();
        if (subCats.isArray()) {
            for (JsonNode sc : subCats) {
                String val = sc.asString(null);
                if (val != null && !val.isBlank()) {
                    categoriesList.add(val);
                }
            }
        } else if (!subCats.isMissingNode() && !subCats.isNull()) {
            String val = subCats.asString(null);
            if (val != null && !val.isBlank()) {
                categoriesList.add(val);
            }
        }
        String category;
        if (categoriesList.isEmpty()) {
            category = null;
        } else {
            category = categoriesList.get(0);
        }

        String imageUrl = null;
        JsonNode images = food.path("food_images").path("food_image");
        if (images.isArray() && !images.isEmpty()) {
            imageUrl = images.get(0).path("image_url").asString(null);
        }

        Map<String, Object> product = new HashMap<>();
        product.put("food_id", food.path("food_id").asString(null));
        product.put("food_url", food.path("food_url").asString(null));
        product.put("name", food.path("food_name").asString(""));
        product.put("brand", food.path("brand_name").asString(null));
        product.put("category", category);
        product.put("categories", categoriesList);
        product.put("image_url", imageUrl);
        product.put("serving_size", serving.path("serving_description").asString(null));
        product.put("calories", num(serving, "calories"));
        product.put("total_fat", num(serving, "fat"));
        product.put("saturated_fat", num(serving, "saturated_fat"));
        product.put("trans_fat", num(serving, "trans_fat"));
        product.put("polyunsaturated_fat", num(serving, "polyunsaturated_fat"));
        product.put("monounsaturated_fat", num(serving, "monounsaturated_fat"));
        product.put("cholesterol", num(serving, "cholesterol"));
        product.put("sodium", num(serving, "sodium"));
        product.put("total_carbs", num(serving, "carbohydrate"));
        product.put("dietary_fiber", num(serving, "fiber"));
        product.put("total_sugars", num(serving, "sugar"));
        product.put("added_sugars", num(serving, "added_sugars"));
        product.put("protein", num(serving, "protein"));
        product.put("raw_json", rawJson);
        return product;
    }

    private Map<String, Object> fetchFromOpenFoodFacts(String gtin) {
        try {
            String body = http.get()
                    .uri(offApiBase + "/{gtin}.json", gtin)
                    .header("User-Agent", offUserAgent)
                    .retrieve().body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);

            if (root.path("status").asInt(0) != 1) {
                return null;
            }

            return parseOpenFoodFacts(root, body);
        } catch (Exception e) {
            log.error("OFF error: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseOpenFoodFacts(JsonNode root, String rawJson) {
        JsonNode prod = root.path("product");
        JsonNode n = prod.path("nutriments");

        Map<String, Object> product = new HashMap<>();
        product.put("name", prod.path("product_name").asString(""));
        product.put("brand", prod.path("brands").asString(null));
        product.put("category", prod.path("categories").asString(null));
        product.put("image_url", prod.path("image_url").asString(null));
        product.put("serving_size", prod.path("serving_size").asString(null));

        product.put("calories", pick(n, "energy-kcal_serving", "energy-kcal_100g"));
        product.put("total_fat", pick(n, "fat_serving", "fat_100g"));
        product.put("saturated_fat", pick(n, "saturated-fat_serving", "saturated-fat_100g"));
        product.put("trans_fat", pick(n, "trans-fat_serving", "trans-fat_100g"));
        product.put("cholesterol", pick(n, "cholesterol_serving", "cholesterol_100g"));
        product.put("sodium", pick(n, "sodium_serving", "sodium_100g"));
        product.put("total_carbs", pick(n, "carbohydrates_serving", "carbohydrates_100g"));
        product.put("dietary_fiber", pick(n, "fiber_serving", "fiber_100g"));
        product.put("total_sugars", pick(n, "sugars_serving", "sugars_100g"));
        product.put("added_sugars", pick(n, "added-sugars_serving", "added-sugars_100g"));
        product.put("protein", pick(n, "proteins_serving", "proteins_100g"));
        product.put("nutrition_grade", prod.path("nutrition_grades").asString(null));
        product.put("raw_json", rawJson);
        return product;
    }

    private BigDecimal num(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asString(""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal pick(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = num(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> withSource(Map<String, Object> product, String source) {
        product.put("source", source);
        return product;
    }

    private Array toSqlTextArray(List<?> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            String[] arr = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i).toString();
            }
            return db.execute((ConnectionCallback<Array>) conn ->
                    conn.createArrayOf("text", arr));
        } catch (Exception e) {
            return null;
        }
    }
    
    ///search stuff
    public List<Map<String, Object>> searchProducts(String query, String categoryId) {
        try {
            String token = getToken();

            if (token == null) return List.of();
            String body = http.get()
                    .uri(fsApiBase + "/foods/search/v1?search_expression={query}&page_number=0&max_results=50&format=json", query)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);
            JsonNode foods = root.path("foods").path("food");
            List<Map<String, Object>> results = new ArrayList<>();

            if (foods.isArray()) {
                for (JsonNode food : foods) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", food.path("food_id").asString());
                    item.put("name", food.path("food_name").asString());
                    item.put("brand", food.path("brand_name").asString(null));
                    results.add(item);
                }
            }

            if (categoryId == null || categoryId.isEmpty()) {
                return results;
            }
            ///filter stuff
            List<Map<String, Object>> filtered = new ArrayList<>();
            List<String> keywords = CATEGORY_KEYWORDS.get(categoryId.toLowerCase());
            if (keywords == null) {
                return results;
            }

            for (Map<String, Object> item : results) {
                String name = (String) item.get("name");
                if (name == null) continue;

                for (String keyword : keywords) {
                    if (name.toLowerCase().contains(keyword)) {
                        filtered.add(item);
                        break;
                    }
                }
            }
            return filtered;

        } catch (Exception e) {
            log.error("Search error: {}", e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> getDetails(String foodId) {
        try {
            String token = getToken();
            if (token == null) {
                return null;
            }

            String body = http.get()
                    .uri(fsApiBase
                            + "/server.api?method=food.get.v2"
                            + "&food_id={id}"
                            + "&flag_default_serving=true"
                            + "&include_sub_categories=true"
                            + "&include_food_images=true"
                            + "&format=json",
                            foodId)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            JsonNode root = JsonMapper.shared().readTree(body);
            JsonNode food = root.path("food");
            JsonNode servingsNode = food.path("servings").path("serving");

            List<Map<String, Object>> allServings = new ArrayList<>();
            JsonNode defaultServing = null;

            if (servingsNode.isArray()) {
                for (JsonNode s : servingsNode) {
                    allServings.add(servingToMap(s));
                    if (s.path("is_default").asInt(0) == 1) {
                        defaultServing = s;
                    }
                }
                if (defaultServing == null && !servingsNode.isEmpty()) {
                    defaultServing = servingsNode.get(0);
                }
            } else if (!servingsNode.isMissingNode()) {
                allServings.add(servingToMap(servingsNode));
                defaultServing = servingsNode;
            }

            if (defaultServing == null) {
                defaultServing = JsonMapper.shared().createObjectNode();
            }

            String imageUrl = null;
            JsonNode images = food.path("food_images").path("food_image");
            if (images.isArray() && !images.isEmpty()) {
                imageUrl = images.get(0).path("image_url").asString(null);
            }

            List<String> categories = new ArrayList<>();
            JsonNode subCats = food.path("food_sub_categories").path("food_sub_category");
            if (subCats.isArray()) {
                for (JsonNode sc : subCats) {
                    String val = sc.asString(null);
                    if (val != null && !val.isBlank()) {
                        categories.add(val);
                    }
                }
            } else if (!subCats.isMissingNode() && !subCats.isNull()) {
                String val = subCats.asString(null);
                if (val != null && !val.isBlank()) {
                    categories.add(val);
                }
            }

            String ingredients = food.path("food_attributes").path("ingredient").path("value").asString(null);

            Map<String, Object> result = new HashMap<>();
            result.put("id", foodId);
            result.put("name", food.path("food_name").asString(null));
            result.put("brand", food.path("brand_name").asString(null));
            result.put("image_url", imageUrl);
            result.put("categories", categories);
            result.put("ingredients", ingredients);
            result.put("all_servings", allServings);

            result.put("serving_size", defaultServing.path("serving_description").asString(null));
            result.put("calories", num(defaultServing, "calories"));
            result.put("total_fat", num(defaultServing, "fat"));
            result.put("saturated_fat", num(defaultServing, "saturated_fat"));
            result.put("trans_fat", num(defaultServing, "trans_fat"));
            result.put("polyunsaturated_fat", num(defaultServing, "polyunsaturated_fat"));
            result.put("monounsaturated_fat", num(defaultServing, "monounsaturated_fat"));
            result.put("cholesterol", num(defaultServing, "cholesterol"));
            result.put("sodium", num(defaultServing, "sodium"));
            result.put("total_carbs", num(defaultServing, "carbohydrate"));
            result.put("dietary_fiber", num(defaultServing, "fiber"));
            result.put("total_sugars", num(defaultServing, "sugar"));
            result.put("added_sugars", num(defaultServing, "added_sugars"));
            result.put("protein", num(defaultServing, "protein"));

            return result;

        } catch (Exception e) {
            log.error("Error fetching food details: {}", e.getMessage());
            return null;
        }
    }
    /// new ategories stuff yay!
    public List<Map<String, Object>> getFoodCategories() {
        try {
            String token = getToken();
            if (token == null) return List.of();
            String body = http.get()
                    .uri(fsApiBase + "/server.api?method=food_categories.get.v2&format=json")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);
            JsonNode categories = root.path("food_categories").path("food_category");
            List<Map<String, Object>> results = new ArrayList<>();
            if (categories.isArray()) {
                for (JsonNode cat : categories) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", cat.path("food_category_id").asText());
                    item.put("name", cat.path("food_category_name").asText());
                    item.put("description", cat.path("food_category_description").asText(null));
                    results.add(item);
                }
            }
            return results;
        } catch (Exception e) {
            System.err.println("Category fetch error: " + e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> servingToMap(JsonNode s) {
        Map<String, Object> m = new HashMap<>();
        m.put("serving_id", s.path("serving_id").asString(null));
        m.put("serving_description", s.path("serving_description").asString(null));
        m.put("serving_url", s.path("serving_url").asString(null));
        m.put("metric_serving_amount", s.path("metric_serving_amount").asString(null));
        m.put("metric_serving_unit", s.path("metric_serving_unit").asString(null));
        m.put("calories", num(s, "calories"));
        m.put("total_fat", num(s, "fat"));
        m.put("saturated_fat", num(s, "saturated_fat"));
        m.put("trans_fat", num(s, "trans_fat"));
        m.put("polyunsaturated_fat", num(s, "polyunsaturated_fat"));
        m.put("monounsaturated_fat", num(s, "monounsaturated_fat"));
        m.put("cholesterol", num(s, "cholesterol"));
        m.put("sodium", num(s, "sodium"));
        m.put("total_carbs", num(s, "carbohydrate"));
        m.put("dietary_fiber", num(s, "fiber"));
        m.put("total_sugars", num(s, "sugar"));
        m.put("added_sugars", num(s, "added_sugars"));
        m.put("protein", num(s, "protein"));
        return m;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }

}