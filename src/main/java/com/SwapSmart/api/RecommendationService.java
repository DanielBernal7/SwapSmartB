package com.SwapSmart.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RecommendationService {
    private final ProductService productService;
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate namedDb;
    private final RestClient http;

    @Value("${fatsecret.api-base-url}")
    private String fsApiBase;

    private static final int MAX_RECOMMENDATIONS = 5;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 2;

    public static final Set<String> VALID_CRITERIA = Set.of(
            "sugar", "sodium", "calories");

    private static final Map<String, String> CRITERIA_FIELDS = Map.of(
            "sugar", "total_sugars",
            "sodium", "sodium",
            "calories", "calories");

    public record RecommendationResult(
            Map<String, Object> scanned,
            List<Map<String, Object>> recommendations,
            String criteria) {
    }

    private static final Set<String> NOISE_WORDS = Set.of(
            "the", "and", "with", "from", "natural", "organic", "original",
            "classic", "style", "flavored", "flavor", "lite", "light", "free",
            "reduced", "low", "fat", "sugar", "calorie", "diet", "zero",
            "new", "extra", "super", "ultra", "mega", "big", "mini", "small",
            "large", "family", "size", "pack", "count", "whole", "real",
            "made", "fresh", "pure", "raw", "plain", "unsweetened", "sweetened",
            "crunchy", "crispy", "smooth", "creamy", "chunky", "thick", "thin",
            "hot", "cold", "iced", "frozen", "toasted", "roasted", "baked",
            "fried", "grilled", "honey", "vanilla", "chocolate", "strawberry",
            "blueberry", "raspberry", "lemon", "lime", "orange", "apple",
            "cinnamon", "maple", "caramel", "peanut", "almond", "coconut",
            "oats", "wheat", "grain", "multi", "protein", "fiber", "vitamin",
            "nature", "valley", "general", "mills", "kelloggs", "quaker",
            "nabisco", "kraft", "nestle", "pepsi", "coca", "cola", "frito",
            "lays", "doritos", "cheetos");

    private static final List<String> FOOD_TYPE_PHRASES = List.of(
            "granola bars", "granola bar", "protein bars", "protein bar",
            "energy bars", "energy bar", "cereal bars", "cereal bar",
            "fruit snacks", "fruit snack", "potato chips", "tortilla chips",
            "corn chips", "pita chips", "veggie chips", "rice cakes",
            "ice cream", "frozen yogurt", "greek yogurt", "yogurt",
            "orange juice", "apple juice", "grape juice", "cranberry juice",
            "fruit juice", "sports drink", "energy drink", "soft drink",
            "chocolate milk", "almond milk", "oat milk", "soy milk",
            "peanut butter", "almond butter", "cream cheese", "cottage cheese",
            "sour cream", "whipped cream", "mac and cheese", "mac & cheese",
            "instant oatmeal", "oatmeal", "breakfast cereal", "cereal",
            "trail mix", "mixed nuts", "fruit cups", "pudding cups",
            "graham crackers", "animal crackers", "crackers", "cookies",
            "brownie", "muffins", "muffin", "pancake mix", "waffle",
            "bread", "bagels", "bagel", "tortillas", "pasta sauce",
            "salad dressing", "barbecue sauce", "ketchup", "mustard",
            "mayonnaise", "salsa", "hummus", "guacamole", "soda", "cola",
            "gummy", "candy", "chocolate", "chips", "pretzels", "popcorn",
            "jerky", "nuts", "bars", "jam", "jelly", "syrup");

    private final UsdaService usdaService;

    public RecommendationService(ProductService productService, JdbcTemplate db,
            UsdaService usdaService) {
        this.productService = productService;
        this.db = db;
        this.namedDb = new NamedParameterJdbcTemplate(db);
        this.http = RestClient.create();
        this.usdaService = usdaService;
    }

    public RecommendationResult recommend(String gtin, String criteria) {
        String field = CRITERIA_FIELDS.getOrDefault(criteria, "total_sugars");

        Map<String, Object> scanned = productService.lookupProduct(gtin);
        if (scanned == null) {
            return null;
        }

        Map<String, Object> scannedSummary = buildSummary(scanned);

        if ("sugar".equals(criteria)) {
            List<Map<String, Object>> cached = getCachedAlternatives(gtin);
            if (cached != null) {
                System.out.println("Recommendations from CACHE for gtin: " + gtin);
                return new RecommendationResult(scannedSummary, toRecommendationSummaries(cached), criteria);
            }
        }

        Object valueRaw = scanned.get(field);
        if (valueRaw == null) {
            return new RecommendationResult(scannedSummary, Collections.emptyList(), criteria);
        }

        BigDecimal scannedValue = toBigDecimal(valueRaw.toString());
        if (scannedValue == null) {
            return new RecommendationResult(scannedSummary, Collections.emptyList(), criteria);
        }

        Object nameRaw = scanned.get("name");
        if (nameRaw == null || nameRaw.toString().isBlank()) {
            return new RecommendationResult(scannedSummary, Collections.emptyList(), criteria);
        }

        String productType = resolveProductType(scanned, gtin);

        String brandRaw = Objects.toString(scanned.get("brand"), "");

        String searchQuery;
        if (productType != null && !productType.isBlank()) {
            searchQuery = productType;
            System.out.println("Product-type search: \"" + searchQuery + "\" criteria=" + criteria);
        } else {
            Set<String> scannedCategories = getCategories(scanned);
            if (scannedCategories.isEmpty() && scanned.get("category") != null) {
                scannedCategories.add(scanned.get("category").toString().toLowerCase().trim());
            }
            Set<String> usableCategories = new LinkedHashSet<>();
            for (String cat : scannedCategories) {
                String norm;
                if (cat.startsWith("en:")) {
                    norm = cat.substring(3).replace('-', ' ');
                } else {
                    norm = cat;
                }
                if (!norm.isBlank() && !GENERIC_CATEGORIES.contains(norm)) {
                    usableCategories.add(norm);
                }
            }
            String category = Objects.toString(scanned.get("category"), "");
            searchQuery = buildSearchQuery(nameRaw.toString(), category, !usableCategories.isEmpty());
            System.out.println("Name-token search: \"" + searchQuery + "\" criteria=" + criteria);
        }

        Map<String, Map<String, Object>> seenById = new LinkedHashMap<>();

        if (!brandRaw.isBlank()) {
            System.out.println("Brand search: \"" + brandRaw + "\"");
            for (Map<String, Object> c : fetchAllCandidates(brandRaw, gtin)) {
                seenById.put(candidateId(c), c);
            }
        }

        if (productType != null) {
            List<Map<String, Object>> dbCandidates = getCandidatesFromDb(productType, field, gtin);
            System.out.println("DB candidates for product_type=\"" + productType + "\": " + dbCandidates.size());
            for (Map<String, Object> c : dbCandidates) {
                seenById.putIfAbsent(candidateId(c), c);
            }
        }

        Set<String> distinctBrandSet = new LinkedHashSet<>();
        for (Map<String, Object> c : seenById.values()) {
            String b = Objects.toString(c.get("brand"), "").toLowerCase().trim();
            if (!b.isBlank()) {
                distinctBrandSet.add(b);
            }
        }
        long distinctBrands = distinctBrandSet.size();
        boolean isDiverse = seenById.size() >= MAX_RECOMMENDATIONS * 4 && distinctBrands >= MAX_RECOMMENDATIONS;
        if (!isDiverse) {
            System.out.println("API search: \"" + searchQuery + "\"");
            for (Map<String, Object> c : fetchAllCandidates(searchQuery, gtin)) {
                seenById.putIfAbsent(candidateId(c), c);
            }
        }

        String scannedServing = Objects.toString(scanned.get("serving_size"), "");
        boolean scannedLiquid = isLiquidServing(scannedServing);
        boolean scannedNonLiquid = isNonLiquidServing(scannedServing);

        List<Map<String, Object>> passing = new ArrayList<>();
        for (Map<String, Object> c : seenById.values()) {
            Object raw = c.get(field);
            if (raw == null) {
                continue;
            }
            BigDecimal val = toBigDecimal(raw.toString());
            if (val == null || val.compareTo(scannedValue) >= 0) {
                continue;
            }

            String candidateServing;
            if (c.get("serving_size") != null) {
                candidateServing = c.get("serving_size").toString();
            } else {
                candidateServing = "";
            }
            if (scannedLiquid && isNonLiquidServing(candidateServing)) {
                continue;
            }
            if (scannedNonLiquid && isLiquidServing(candidateServing)) {
                continue;
            }

            if (productType != null) {
                String candidateType = deriveCandidateProductType(c);
                if (candidateType != null && !candidateType.equals(productType)) {
                    continue;
                }
            }

            BigDecimal totalSugars = toBigDecimal(str(c, "total_sugars"));
            if (c.get("added_sugars") == null) {
                if (totalSugars != null && totalSugars.compareTo(BigDecimal.ZERO) == 0) {
                    c.put("added_sugars", BigDecimal.ZERO);
                } else {
                    continue;
                }
            }

            passing.add(c);
        }

        double[] scannedVec = nutritionVectorForCriteria(scanned, criteria);
        passing.sort(Comparator.comparingDouble(
                c -> euclideanDistance(scannedVec, nutritionVectorForCriteria((Map<String, Object>) c, criteria))));

        List<Map<String, Object>> recommendations = new ArrayList<>();
        Map<String, Integer> brandCounts = new LinkedHashMap<>();
        for (Map<String, Object> c : passing) {
            if (recommendations.size() >= MAX_RECOMMENDATIONS) {
                break;
            }
            String brand = Objects.toString(c.get("brand"), "").toLowerCase().trim();
            int count = brandCounts.getOrDefault(brand, 0);
            if (!brand.isBlank() && count >= 2) {
                continue;
            }
            brandCounts.put(brand, count + 1);
            recommendations.add(c);
        }

        if ("sugar".equals(criteria)) {
            saveAlternatives(gtin, productType, scannedValue, recommendations);
        }

        System.out.println("Returning " + recommendations.size()
                + " recommendations (" + criteria + ") for gtin: " + gtin);
        return new RecommendationResult(scannedSummary, toRecommendationSummaries(recommendations), criteria);
    }

    private List<Map<String, Object>> fetchAllCandidates(String query, String excludeGtin) {
        List<Map<String, Object>> allCandidates = new ArrayList<>();
        int totalResults = Integer.MAX_VALUE;

        for (int page = 0; page < MAX_PAGES; page++) {
            if (page > 0 && allCandidates.size() >= totalResults) {
                System.out.println("No more pages — stopping after page " + page);
                break;
            }

            SearchPage result = fetchPage(query, page);
            if (result == null) {
                break;
            }

            totalResults = result.totalResults();
            System.out.println("FatSecret search \"" + query + "\": "
                    + "page " + page + ", total_results=" + totalResults
                    + ", fetching up to "
                    + Math.min(totalResults, PAGE_SIZE * MAX_PAGES) + " candidates");

            if (result.foods().isEmpty()) {
                break;
            }

            for (Map<String, Object> food : result.foods()) {
                sideCacheProduct(food);

                String candidateGtin = (String) food.get("gtin");
                if (candidateGtin != null && candidateGtin.equals(excludeGtin)) {
                    continue;
                }

                allCandidates.add(food);
            }
        }

        System.out.println("Total candidates fetched: " + allCandidates.size());
        return allCandidates;
    }

    private SearchPage fetchPage(String query, int pageNumber) {
        try {
            String token = productService.getToken();

            String body = http.get()
                    .uri(fsApiBase + "/server.api"
                            + "?method=foods.search.v5"
                            + "&region=US"
                            + "&search_expression={query}"
                            + "&page_number={page}"
                            + "&max_results={maxResults}"
                            + "&food_type=brand"
                            + "&flag_default_serving=true"
                            + "&include_sub_categories=true"
                            + "&include_food_images=true"
                            + "&format=json",
                            query, pageNumber, PAGE_SIZE)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                System.err.println("FatSecret search page " + pageNumber + ": empty response body");
                return new SearchPage(0, List.of());
            }
            if (!body.trim().startsWith("{")) {
                System.err.println("FatSecret search page " + pageNumber
                        + ": unexpected response (not JSON): "
                        + body.substring(0, Math.min(300, body.length())));
                return new SearchPage(0, List.of());
            }

            JsonNode root = JsonMapper.shared().readTree(body);
            JsonNode envelope = root.path("foods_search");
            int totalResults = envelope.path("total_results").asInt(0);
            JsonNode foodsNode = envelope.path("results").path("food");

            List<Map<String, Object>> foods = new ArrayList<>();
            if (foodsNode.isArray()) {
                for (JsonNode food : foodsNode) {
                    Map<String, Object> parsed = parseFoodResult(food);
                    if (parsed != null) {
                        foods.add(parsed);
                    }
                }
            }

            System.out.println("  Page " + pageNumber + ": fetched "
                    + foods.size() + " valid items (of " + totalResults + " total)");
            return new SearchPage(totalResults, foods);

        } catch (Exception e) {
            System.err.println("FatSecret v5 search error (page "
                    + pageNumber + "): " + e.getMessage());
            return null;
        }
    }

    private record SearchPage(int totalResults, List<Map<String, Object>> foods) {
    }

    private Map<String, Object> parseFoodResult(JsonNode food) {
        JsonNode servingsNode = food.path("servings").path("serving");
        JsonNode serving = null;

        if (servingsNode.isArray()) {
            for (JsonNode s : servingsNode) {
                if (s.path("is_default").asInt(0) == 1) {
                    serving = s;
                    break;
                }
            }
            if (serving == null && !servingsNode.isEmpty()) {
                serving = servingsNode.get(0);
            }
        } else if (servingsNode.isObject()) {
            serving = servingsNode;
        }

        if (serving == null) {
            return null;
        }

        BigDecimal sugar = numNode(serving, "sugar");
        if (sugar == null) {
            return null;
        }

        BigDecimal calories = numNode(serving, "calories");
        if (calories == null) {
            return null;
        }

        BigDecimal protein = numNode(serving, "protein");
        BigDecimal fat = numNode(serving, "fat");
        if (protein == null && fat == null) {
            return null;
        }

        List<String> categoriesList = extractAllSubCategories(
                food.path("food_sub_categories").path("food_sub_category"));
        String categoryVal;
        if (categoriesList.isEmpty()) {
            categoryVal = null;
        } else {
            categoryVal = categoriesList.get(0);
        }

        String imageUrl = null;
        JsonNode images = food.path("food_images").path("food_image");
        if (images.isArray() && !images.isEmpty()) {
            imageUrl = stringValue(images.get(0).path("image_url"));
        }

        String gtin = stringValue(food.path("food_barcode"));
        String foodId = stringValue(food.path("food_id"));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("gtin", gtin);
        item.put("food_id", foodId);
        item.put("name", stringValueOrEmpty(food.path("food_name")));
        item.put("brand", stringValue(food.path("brand_name")));
        item.put("food_type", stringValue(food.path("food_type")));
        item.put("category", categoryVal);
        item.put("serving_size", stringValue(serving.path("serving_description")));
        item.put("calories", calories);
        item.put("total_fat", numNode(serving, "fat"));
        item.put("saturated_fat", numNode(serving, "saturated_fat"));
        item.put("trans_fat", numNode(serving, "trans_fat"));
        item.put("cholesterol", numNode(serving, "cholesterol"));
        item.put("sodium", numNode(serving, "sodium"));
        item.put("total_carbs", numNode(serving, "carbohydrate"));
        item.put("dietary_fiber", numNode(serving, "fiber"));
        item.put("total_sugars", sugar);
        item.put("added_sugars", numNode(serving, "added_sugars"));
        item.put("protein", numNode(serving, "protein"));
        item.put("image_url", imageUrl);
        item.put("categories", categoriesList);
        item.put("source", "fatsecret");

        return item;
    }

    private void sideCacheProduct(Map<String, Object> product) {
        String foodId = (String) product.get("food_id");
        String gtin = (String) product.get("gtin");
        Map<String, Object> params = toParamMap(product);

        try {
            if (gtin != null && !gtin.isBlank()
                    && foodId != null && !foodId.isBlank()) {
                int updated = namedDb.update(
                        "UPDATE fatsecret_products SET gtin = :gtin "
                                + "WHERE food_id = :food_id AND gtin IS NULL",
                        params);
                if (updated > 0) {
                    return;
                }
            }

            if (gtin != null && !gtin.isBlank()) {
                namedDb.update(
                        "INSERT INTO fatsecret_products "
                                + "(gtin, food_id, name, brand, category, categories, serving_size, "
                                + " calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                                + " sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, "
                                + " protein, image_url, product_type) "
                                + "VALUES (:gtin, :food_id, :name, :brand, :category, :categories, :serving_size, "
                                + " :calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                                + " :sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, "
                                + " :protein, :image_url, :product_type) "
                                + "ON CONFLICT (gtin) DO UPDATE SET "
                                + "  food_id = COALESCE(fatsecret_products.food_id, EXCLUDED.food_id), "
                                + "  categories = COALESCE(EXCLUDED.categories, fatsecret_products.categories), "
                                + "  product_type = COALESCE(fatsecret_products.product_type, EXCLUDED.product_type)",
                        params);
            } else if (foodId != null && !foodId.isBlank()) {
                namedDb.update(
                        "INSERT INTO fatsecret_products "
                                + "(food_id, name, brand, category, categories, serving_size, "
                                + " calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                                + " sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, "
                                + " protein, image_url, product_type) "
                                + "VALUES (:food_id, :name, :brand, :category, :categories, :serving_size, "
                                + " :calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                                + " :sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, "
                                + " :protein, :image_url, :product_type) "
                                + "ON CONFLICT (food_id) DO UPDATE SET "
                                + "  product_type = COALESCE(fatsecret_products.product_type, EXCLUDED.product_type)",
                        params);
            }
        } catch (Exception e) {
            System.err.println("Side-cache write failed for gtin="
                    + gtin + " food_id=" + foodId + ": " + e.getMessage());
        }
    }

    private Map<String, Object> toParamMap(Map<String, Object> product) {
        Map<String, Object> params = new HashMap<>();
        params.put("gtin", product.get("gtin"));
        params.put("food_id", product.get("food_id"));
        params.put("name", product.get("name"));
        params.put("brand", product.get("brand"));
        params.put("category", product.get("category"));
        params.put("serving_size", product.get("serving_size"));
        params.put("calories", product.get("calories"));
        params.put("total_fat", product.get("total_fat"));
        params.put("saturated_fat", product.get("saturated_fat"));
        params.put("trans_fat", product.get("trans_fat"));
        params.put("cholesterol", product.get("cholesterol"));
        params.put("sodium", product.get("sodium"));
        params.put("total_carbs", product.get("total_carbs"));
        params.put("dietary_fiber", product.get("dietary_fiber"));
        params.put("total_sugars", product.get("total_sugars"));
        params.put("added_sugars", product.get("added_sugars"));
        params.put("protein", product.get("protein"));
        params.put("image_url", product.get("image_url"));
        params.put("categories", toSqlTextArray(product.get("categories")));
        params.put("product_type", UsdaService.deriveProductType(str(product, "category")));
        return params;
    }

    private Map<String, Object> buildSummary(Map<String, Object> p) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", p.get("name"));
        s.put("brand", p.get("brand"));
        s.put("category", p.get("category"));
        s.put("image_url", p.get("image_url"));
        s.put("serving_size", p.get("serving_size"));
        s.put("calories", p.get("calories"));
        s.put("total_sugars", p.get("total_sugars"));
        s.put("added_sugars", p.get("added_sugars"));
        s.put("sodium", p.get("sodium"));
        s.put("total_fat", p.get("total_fat"));
        s.put("saturated_fat", p.get("saturated_fat"));
        s.put("protein", p.get("protein"));
        s.put("dietary_fiber", p.get("dietary_fiber"));
        s.put("total_carbs", p.get("total_carbs"));
        s.put("sugar_reduction_pct", p.get("sugar_reduction_pct"));
        return s;
    }

    private List<Map<String, Object>> toRecommendationSummaries(List<Map<String, Object>> products) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> p : products) {
            result.add(buildSummary(p));
        }
        return result;
    }

    private String candidateId(Map<String, Object> c) {
        if (c.get("food_id") != null) {
            return c.get("food_id").toString();
        }
        if (c.get("name") != null) {
            return c.get("name").toString();
        }
        return "";
    }

    private List<Map<String, Object>> getCandidatesFromDb(String productType,
            String field, String excludeGtin) {
        if (productType == null || productType.isBlank()) {
            return Collections.emptyList();
        }
        if (!CRITERIA_FIELDS.containsValue(field)) {
            return Collections.emptyList();
        }

        try {
            String sql = "SELECT * FROM fatsecret_products "
                    + "WHERE product_type = ? AND " + field + " IS NOT NULL "
                    + "AND (gtin IS NULL OR gtin != ?) "
                    + "ORDER BY " + field + " ASC LIMIT 60";
            String gtinParam;
            if (excludeGtin != null) {
                gtinParam = excludeGtin;
            } else {
                gtinParam = "";
            }
            return db.queryForList(sql, productType, gtinParam);
        } catch (Exception e) {
            System.err.println("DB candidate query failed: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> getCachedAlternatives(String sourceGtin) {
        try {
            // Single query with LEFT JOINs replaces N+1 individual SELECTs.
            // Tries to match by food_id first, falls back to gtin via COALESCE.
            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT a.sugar_difference, a.sugar_reduction_pct, "
                            + "COALESCE(pf.id, pg.id) AS matched_id, "
                            + "COALESCE(pf.gtin, pg.gtin) AS gtin, "
                            + "COALESCE(pf.food_id, pg.food_id) AS food_id, "
                            + "COALESCE(pf.name, pg.name) AS name, "
                            + "COALESCE(pf.brand, pg.brand) AS brand, "
                            + "COALESCE(pf.category, pg.category) AS category, "
                            + "COALESCE(pf.image_url, pg.image_url) AS image_url, "
                            + "COALESCE(pf.serving_size, pg.serving_size) AS serving_size, "
                            + "COALESCE(pf.calories, pg.calories) AS calories, "
                            + "COALESCE(pf.total_fat, pg.total_fat) AS total_fat, "
                            + "COALESCE(pf.saturated_fat, pg.saturated_fat) AS saturated_fat, "
                            + "COALESCE(pf.trans_fat, pg.trans_fat) AS trans_fat, "
                            + "COALESCE(pf.cholesterol, pg.cholesterol) AS cholesterol, "
                            + "COALESCE(pf.sodium, pg.sodium) AS sodium, "
                            + "COALESCE(pf.total_carbs, pg.total_carbs) AS total_carbs, "
                            + "COALESCE(pf.dietary_fiber, pg.dietary_fiber) AS dietary_fiber, "
                            + "COALESCE(pf.total_sugars, pg.total_sugars) AS total_sugars, "
                            + "COALESCE(pf.added_sugars, pg.added_sugars) AS added_sugars, "
                            + "COALESCE(pf.protein, pg.protein) AS protein "
                            + "FROM alternatives a "
                            + "LEFT JOIN fatsecret_products pf ON pf.food_id = a.alternative_food_id "
                            + "LEFT JOIN fatsecret_products pg ON pg.gtin = a.alternative_gtin "
                            + "WHERE a.source_gtin = ? "
                            + "ORDER BY a.rank",
                    sourceGtin);

            if (rows.isEmpty()) {
                return null;
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (row.get("matched_id") == null) {
                    continue;
                }
                Map<String, Object> product = new LinkedHashMap<>(row);
                product.put("source", "fatsecret");
                results.add(product);
            }

            if (results.isEmpty()) {
                return null;
            }

            return results;

        } catch (Exception e) {
            System.err.println("Alternatives cache read failed: " + e.getMessage());
            return null;
        }
    }

    private void saveAlternatives(String sourceGtin, String productType,
            BigDecimal scannedSugar,
            List<Map<String, Object>> recommendations) {
        int rank = 0;
        for (Map<String, Object> alt : recommendations) {
            rank++;

            String altFoodId = (String) alt.get("food_id");
            String altGtin = (String) alt.get("gtin");

            if (altFoodId == null || altFoodId.isBlank()) {
                System.err.println("Skipping alternative with no food_id: " + alt.get("name"));
                continue;
            }

            try {
                BigDecimal altSugar = null;
                Object altSugarRaw = alt.get("total_sugars");
                if (altSugarRaw != null) {
                    altSugar = toBigDecimal(altSugarRaw.toString());
                }

                BigDecimal sugarDiff = null;
                BigDecimal reductionPct = null;
                if (altSugar != null && scannedSugar.compareTo(BigDecimal.ZERO) > 0) {
                    sugarDiff = scannedSugar.subtract(altSugar)
                            .setScale(2, RoundingMode.HALF_UP);
                    reductionPct = sugarDiff
                            .divide(scannedSugar, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);
                }

                db.update(
                        "INSERT INTO alternatives "
                                + "(source_gtin, alternative_food_id, alternative_gtin, "
                                + " sugar_difference, sugar_reduction_pct, product_type, rank) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (source_gtin, alternative_food_id) DO UPDATE SET "
                                + "  alternative_gtin = EXCLUDED.alternative_gtin, "
                                + "  sugar_difference = EXCLUDED.sugar_difference, "
                                + "  sugar_reduction_pct = EXCLUDED.sugar_reduction_pct, "
                                + "  product_type = EXCLUDED.product_type, "
                                + "  rank = EXCLUDED.rank",
                        sourceGtin, altFoodId, altGtin,
                        sugarDiff, reductionPct, productType, rank);

            } catch (Exception e) {
                System.err.println("Alternatives save failed for food_id=" + altFoodId + ": " + e.getMessage());
            }
        }
    }

    String buildSearchQuery(String productName, String category, boolean hasUsableCategory) {
        String lower = productName.toLowerCase();

        if (hasUsableCategory) {
            for (String phrase : FOOD_TYPE_PHRASES) {
                if (lower.contains(phrase)) {
                    return phrase;
                }
            }
        }

        String[] tokens = lower.replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        List<String> kept = new ArrayList<>();
        for (String t : tokens) {
            if (kept.size() >= 3) {
                break;
            }
            if (t.length() > 2 && !NOISE_WORDS.contains(t) && !t.matches("\\d+")) {
                kept.add(t);
            }
        }

        if (!kept.isEmpty()) {
            return String.join(" ", kept.subList(0, Math.min(3, kept.size())));
        }

        String[] raw = productName.trim().split("\\s+");
        return String.join(" ", Arrays.copyOf(raw, Math.min(2, raw.length)));
    }

    private List<String> extractAllSubCategories(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String val = stringValue(item);
                if (val != null && !val.isBlank()) {
                    result.add(val);
                }
            }
            return result;
        }
        String val = stringValue(node);
        if (val != null && !val.isBlank()) {
            result.add(val);
        }
        return result;
    }

    private Set<String> getCategories(Map<String, Object> product) {
        Object cats = product.get("categories");
        Set<String> result = new LinkedHashSet<>();
        if (cats == null) {
            return result;
        }
        if (cats instanceof Array) {
            try {
                Object[] arr = (Object[]) ((Array) cats).getArray();
                for (Object item : arr) {
                    if (item != null && !item.toString().isBlank()) {
                        result.add(item.toString().toLowerCase().trim());
                    }
                }
            } catch (Exception e) {
                return result;
            }
            return result;
        }
        if (cats instanceof List) {
            for (Object item : (List<?>) cats) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().toLowerCase().trim());
                }
            }
        }
        return result;
    }

    private Array toSqlTextArray(Object value) {
        if (value == null) {
            return null;
        }
        List<?> list;
        if (value instanceof List) {
            list = (List<?>) value;
        } else {
            return null;
        }
        if (list.isEmpty()) {
            return null;
        }
        try {
            String[] arr = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = list.get(i).toString();
            }
            return db.execute((ConnectionCallback<Array>) conn -> conn.createArrayOf("text", arr));
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal numNode(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        try {
            String text = v.asString();
            if (text == null || text.isBlank()) {
                return null;
            }
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.stringValue();
    }

    private String stringValueOrEmpty(JsonNode node) {
        String value = stringValue(node);
        if (value != null) {
            return value;
        }
        return "";
    }

    private BigDecimal toBigDecimal(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Set<String> GENERIC_CATEGORIES = Set.of(
            "snacks", "beverages", "foods", "other", "misc", "general",
            "frozen foods", "canned foods", "condiments", "prepared foods");

    private String resolveProductType(Map<String, Object> scanned, String gtin) {
        String productType = str(scanned, "product_type");

        if (productType == null) {
            String scannedGtin = str(scanned, "gtin");
            String scannedFoodId = str(scanned, "food_id");
            String category = str(scanned, "category");
            usdaService.enrichFatSecretProduct(scannedGtin, scannedFoodId, category);
            if (scannedGtin != null) {
                List<Map<String, Object>> rows = db.queryForList(
                        "SELECT product_type FROM fatsecret_products WHERE gtin = ?", scannedGtin);
                if (!rows.isEmpty() && rows.getFirst().get("product_type") != null) {
                    productType = rows.getFirst().get("product_type").toString();
                }
            }
            if (productType == null && scannedFoodId != null) {
                List<Map<String, Object>> rows = db.queryForList(
                        "SELECT product_type FROM fatsecret_products WHERE food_id = ?", scannedFoodId);
                if (!rows.isEmpty() && rows.getFirst().get("product_type") != null) {
                    productType = rows.getFirst().get("product_type").toString();
                }
            }
        }

        return productType;
    }

    private double[] nutritionVectorForCriteria(Map<String, Object> product, String criteria) {
        switch (criteria) {
            case "sugar":
                return new double[] {
                        toDouble(product.get("total_fat")),
                        toDouble(product.get("protein")),
                        toDouble(product.get("sodium")) / 100.0
                };
            case "sodium":
                return new double[] {
                        toDouble(product.get("calories")) / 10.0,
                        toDouble(product.get("total_fat")),
                        toDouble(product.get("protein")),
                        toDouble(product.get("total_sugars"))
                };
            case "calories":
                return new double[] {
                        toDouble(product.get("total_fat")),
                        toDouble(product.get("protein")),
                        toDouble(product.get("total_sugars")),
                        toDouble(product.get("sodium")) / 100.0
                };
            default:
                return nutritionVector(product);
        }
    }

    private double[] nutritionVector(Map<String, Object> product) {
        return new double[] {
                toDouble(product.get("calories")) / 10.0,
                toDouble(product.get("total_fat")),
                toDouble(product.get("protein")),
                toDouble(product.get("total_carbs")),
                toDouble(product.get("sodium")) / 100.0
        };
    }

    private double euclideanDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum = sum + diff * diff;
        }
        return Math.sqrt(sum);
    }

    private String deriveCandidateProductType(Map<String, Object> candidate) {
        return UsdaService.deriveProductType(str(candidate, "category"));
    }

    private boolean isLiquidServing(String serving) {
        if (serving == null) {
            return false;
        }
        String lower = serving.toLowerCase();
        return lower.contains("fl oz") || lower.contains("bottle")
                || lower.contains("can") || lower.contains("liter")
                || (lower.contains("ml") && !lower.contains("small"));
    }

    private boolean isNonLiquidServing(String serving) {
        if (serving == null) {
            return false;
        }
        String lower = serving.toLowerCase();
        return lower.contains("bar") || lower.contains("scoop")
                || lower.contains("tbsp") || lower.contains("tsp")
                || lower.contains("piece") || lower.contains("tablet")
                || lower.contains("capsule") || lower.contains("packet")
                || lower.contains("package") || lower.contains("slice")
                || lower.contains("cookie") || lower.contains("bag")
                || lower.matches(".*\\bpack\\b.*");
    }

    private double toDouble(Object val) {
        if (val == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
