package com.SwapSmart.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class ProductService {
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate namedDb;
    private final RestClient http;

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

    public ProductService(JdbcTemplate db) {
        this.db = db;
        this.namedDb = new NamedParameterJdbcTemplate(db);
        this.http = RestClient.create();
    }

    // This the main focus cache first then API lookups.
    // FatSecret Cache and API main for both categories
    public Map<String, Object> lookupProduct(String gtin) {

        // 1. Check our FatSecret cache first
        Map<String, Object> product = findCached("fatsecret_products", "gtin", gtin);
        if (product != null) {
            System.out.println("FatSecret CACHE (by gtin)");
            return withSource(product, "fatsecret");
        }

        // 2. Check OFF cache
        product = findCached("openfoodfacts_products", "gtin", gtin);
        if (product != null) {
            System.out.println("Open Food Facts CACHE");
            return withSource(product, "openfoodfacts");
        }

        // 3. Actually call FatSecret API
        product = fetchFromFatSecret(gtin);
        if (product != null) {
            System.out.println("FatSecret API");
            cacheFatSecretProduct(gtin, product);
            return withSource(product, "fatsecret");
        }

        // 4. Try Open Food Facts as fallback
        product = fetchFromOpenFoodFacts(gtin);
        if (product != null) {
            System.out.println("Open Food Facts API");
            cacheProduct("openfoodfacts_products", gtin, product);
            return withSource(product, "openfoodfacts");
        }

        // Not found anywhere
        return null;
    }

    // Cache lookup

    // Checks if we already have this product saved in our DB.
    // The table and column params let us reuse this for both fatsecret and OFF
    // tables.
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

    // FatSecret cache write — the tricky part is deduplication
    //
    // Here's the problem: when RecommendationService searches for "granola",
    // it caches ~99 products( I don't remember if I left it 99 or 100) by food_id
    // with gtin=NULL. Later when someone
    // scans one of those products by barcode, we don't want to create a
    // duplicate row. Instead we find the existing food_id row and add the
    // gtin to it.

    private void cacheFatSecretProduct(String gtin, Map<String, Object> product) {
        product.put("gtin", gtin);
        String foodId = (String) product.get("food_id");

        try {
            if (foodId != null && !foodId.isBlank()) {
                int updated = db.update(
                        "UPDATE fatsecret_products SET "
                                + "gtin = ?, name = ?, brand = ?, category = ?, "
                                // COALESCE keeps whichever image_url isn't null (this is for future plans)
                                + "image_url = COALESCE(?, image_url), "
                                + "serving_size = ?, calories = ?, total_fat = ?, "
                                + "saturated_fat = ?, trans_fat = ?, cholesterol = ?, "
                                + "sodium = ?, total_carbs = ?, dietary_fiber = ?, "
                                + "total_sugars = ?, added_sugars = ?, protein = ?, "
                                + "raw_json = CAST(? AS jsonb) "
                                + "WHERE food_id = ? AND gtin IS NULL",
                        gtin, product.get("name"), product.get("brand"),
                        product.get("category"), product.get("image_url"),
                        product.get("serving_size"),
                        product.get("calories"), product.get("total_fat"),
                        product.get("saturated_fat"), product.get("trans_fat"),
                        product.get("cholesterol"), product.get("sodium"),
                        product.get("total_carbs"), product.get("dietary_fiber"),
                        product.get("total_sugars"), product.get("added_sugars"),
                        product.get("protein"), product.get("raw_json"),
                        foodId);

                if (updated > 0) {
                    System.out.println("  Merged barcode " + gtin
                            + " into existing food_id " + foodId);
                    return; // done, no duplicate created
                }
            }

            // No existing row to merge into, just insert fresh.
            // Using named params here because there's a way too many columns and
            // ? would be impossible to keep track of.
            String cols = "gtin, food_id, name, brand, category, image_url, serving_size, "
                    + "calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                    + "sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, "
                    + "protein, raw_json";
            String vals = ":gtin, :food_id, :name, :brand, :category, :image_url, :serving_size, "
                    + ":calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                    + ":sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, "
                    + ":protein, CAST(:raw_json AS jsonb)";

            namedDb.update(
                    "INSERT INTO fatsecret_products (" + cols + ") "
                            + "VALUES (" + vals + ") "
                            // If this gtin already exists, just backfill food_id and image
                            + "ON CONFLICT (gtin) DO UPDATE SET "
                            + "  food_id = COALESCE(fatsecret_products.food_id, EXCLUDED.food_id), "
                            + "  image_url = COALESCE(EXCLUDED.image_url, fatsecret_products.image_url)",
                    product);
        } catch (Exception e) {
            System.err.println("FatSecret cache write failed: " + e.getMessage());
        }
    }

    // Simpler cache write for OFF products
    private void cacheProduct(String table, String gtin, Map<String, Object> product) {
        product.put("gtin", gtin);

        // Maybe I should have written this as a final value instead... I'll think about
        // this
        // I'm not even sure if it will work.
        String cols = "gtin, name, brand, category, serving_size, calories, "
                + "total_fat, saturated_fat, trans_fat, cholesterol, sodium, "
                + "total_carbs, dietary_fiber, total_sugars, added_sugars, protein, "
                + "raw_json";
        String vals = ":gtin, :name, :brand, :category, :serving_size, :calories, "
                + ":total_fat, :saturated_fat, :trans_fat, :cholesterol, :sodium, "
                + ":total_carbs, :dietary_fiber, :total_sugars, :added_sugars, :protein, "
                + "CAST(:raw_json AS jsonb)";

        // OFF (or right when I say this I mean OpenFoodFacts I got tired writing it)
        // has an extra column for their A-E nutrition grade
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
            System.err.println("Cache write failed: " + e.getMessage());
        }
    }

    // FatSecret API — barcode lookup
    // Docs: https://platform.fatsecret.com/docs/v2/food.find_id_for_barcode
    // This endpoint takes a barcode and gives back the full food object
    // including food_id, nutrition data, images, categories, really everything.
    private Map<String, Object> fetchFromFatSecret(String gtin) {
        try {
            String token = getToken();
            if (token == null) {
                return null;
            }

            // FatSecret wants GTIN-13 (13 digits). Phone scanners usually give us
            // UPC-A (12 digits), so we pad with a leading zero.
            // "016000437791" (12 chars) becomes "0016000437791" (13 chars)
            // there are cases where this might not work
            // One of them I had to pad a 0 on the end and one in the beginning. So that's
            // something we might
            // have to address later.
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

            // FatSecret returns {"error": {...}} if the barcode isn't in their database
            if (root.has("error")) {
                return null;
            }

            return parseFatSecret(root, body);
        } catch (Exception e) {
            System.err.println("FatSecret error: " + e.getMessage());
            return null;
        }
    }

    // OAuth2 token — FatSecret requires this on every request.
    // We cache it and only refresh when it's about to expire.
    // Package-private so RecommendationService can also use it for search calls
    // Otherwise it will break reocomendation service. I spent to long trying to
    // figure out why it wasn't working
    String getToken() {
        // If it's still valid then we just reuse it.
        if (fsToken != null && Instant.now().isBefore(fsTokenExpiry)) {
            return fsToken;
        }

        try {
            // Standard OAuth2 client credentials flow:
            // Base64-encode "clientId:clientSecret", POST it to the token URL
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

            // Refresh 60 seconds early so we never use an expired token
            // We might want to do something differnt here. Just as a failsafe just in case
            // something
            // goes terribly wrong
            fsTokenExpiry = Instant.now().plusSeconds(tokenJson.get("expires_in").asInt() - 60);

            return fsToken;
        } catch (Exception e) {
            System.err.println("FatSecret token error: " + e.getMessage());
            return null;
        }
    }

    // FatSecret response parsing
    //
    // Their API returns deeply nested JSON. We pull out just the fields we
    // need and flatten them into a Map that matches our DB columns.
    //
    // The response looks roughly like:
    // { "food": { "food_id": "60938", "food_name": "Granola",
    // "servings": { "serving": [{ "calories": "210", "sugar": "16" }] } } }

    private Map<String, Object> parseFatSecret(JsonNode root, String rawJson) {
        JsonNode food = root.path("food");
        JsonNode servings = food.path("servings").path("serving");
        JsonNode subCats = food.path("food_sub_categories").path("food_sub_category");

        // Products can have multiple servings (e.g. "1 cup", "100g", "1 bar").
        // We want the one flagged as default, otherwise just grab the first one.
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
            // Sometimes FatSecret returns a single object instead of an array
            serving = servings;
        }

        // Category can be an array ["Granola", "Cereal"] or just a string "Granola"
        String category = null;
        if (subCats.isArray() && !subCats.isEmpty()) {
            category = subCats.get(0).asString(null);
        } else if (!subCats.isMissingNode() && !subCats.isNull()) {
            category = subCats.asString(null);
        }

        // Grab the first product image if they gave us any (usually there is not image
        // which will have to be addressed)
        String imageUrl = null;
        JsonNode images = food.path("food_images").path("food_image");
        if (images.isArray() && !images.isEmpty()) {
            imageUrl = images.get(0).path("image_url").asString(null);
        }

        // Build the product map, keys should match our DB column names exactly
        Map<String, Object> product = new HashMap<>();
        product.put("food_id", food.path("food_id").asString(null));
        product.put("name", food.path("food_name").asString(""));
        product.put("brand", food.path("brand_name").asString(null));
        product.put("category", category);
        product.put("image_url", imageUrl);
        product.put("serving_size", serving.path("serving_description").asString(null));
        product.put("calories", num(serving, "calories"));
        product.put("total_fat", num(serving, "fat"));
        product.put("saturated_fat", num(serving, "saturated_fat"));
        product.put("trans_fat", num(serving, "trans_fat"));
        product.put("cholesterol", num(serving, "cholesterol"));
        product.put("sodium", num(serving, "sodium"));
        product.put("total_carbs", num(serving, "carbohydrate"));
        product.put("dietary_fiber", num(serving, "fiber"));
        product.put("total_sugars", num(serving, "sugar"));
        product.put("added_sugars", num(serving, "added_sugars"));
        product.put("protein", num(serving, "protein"));
        product.put("raw_json", rawJson); // keep the raw response for debugging
        return product;
    }

    // Open Food Facts — free fallback API
    // https://world.openfoodfacts.org/data
    // Community-maintained, so data quality varies a lot
    private Map<String, Object> fetchFromOpenFoodFacts(String gtin) {
        try {
            String body = http.get()
                    .uri(offApiBase + "/{gtin}.json", gtin)
                    .header("User-Agent", offUserAgent) // OFF requires this
                    .retrieve().body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);

            // OFF uses status: 1 = found, 0 = not found
            if (root.path("status").asInt(0) != 1) {
                return null;
            }

            return parseOpenFoodFacts(root, body);
        } catch (Exception e) {
            System.err.println("OFF error: " + e.getMessage());
            return null;
        }
    }

    // OFF uses completely different field names than FatSecret so we have to
    // map everything over to our schema
    private Map<String, Object> parseOpenFoodFacts(JsonNode root, String rawJson) {
        JsonNode prod = root.path("product");
        JsonNode n = prod.path("nutriments");

        Map<String, Object> product = new HashMap<>();
        product.put("name", prod.path("product_name").asString(""));
        product.put("brand", prod.path("brands").asString(null));
        product.put("category", prod.path("categories").asString(null));
        product.put("image_url", prod.path("image_url").asString(null));
        product.put("serving_size", prod.path("serving_size").asString(null));

        // OFF gives us per-serving AND per-100g values.
        // pick() tries per-serving first, falls back to per-100g.
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

    // Helpers. these just extract values from JSON without blowing up (i.e.
    // creating problems or throwing errors)
    // if something's missing or weird. They return null instead of crashing.

    // Pulls a numeric value out of a JSON node. FatSecret sends numbers as
    // strings ("16.00" not 16.00) so we have to parse them.
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

    // Tries multiple field names and returns the first one that has a value.
    // Used for OFF where nutrition can be per-serving or per-100g.
    // String... means you can pass in as many field names as you want.
    private BigDecimal pick(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = num(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    // Adds a "source" tag so the frontend knows where the data came from
    // Used for testing mainly. We might want to remove this later.
    private Map<String, Object> withSource(Map<String, Object> product, String source) {
        product.put("source", source);
        return product;
    }
}