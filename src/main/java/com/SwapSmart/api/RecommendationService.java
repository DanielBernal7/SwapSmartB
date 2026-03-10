package com.SwapSmart.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
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

    private static final Set<String> EXCLUDED_CATEGORIES = Set.of(
            "supplements", "protein powder", "drink mixes",
            "baby foods", "infant formula");

    private static final Set<String> GENERIC_CATEGORIES = Set.of(
            "snacks", "beverages", "foods", "other", "misc", "general",
            "frozen foods", "canned foods", "condiments", "prepared foods");

    public RecommendationService(ProductService productService, JdbcTemplate db) {
        this.productService = productService;
        this.db = db;
        this.namedDb = new NamedParameterJdbcTemplate(db);
        this.http = RestClient.create();
    }

    public RecommendationResult recommend(String gtin, String criteria) {
        String field = CRITERIA_FIELDS.getOrDefault(criteria, "total_sugars");

        Map<String, Object> scanned = productService.lookupProduct(gtin);
        if (scanned == null) {
            return null;
        }

        Map<String, Object> scannedSummary = new LinkedHashMap<>();
        scannedSummary.put("name", scanned.get("name"));
        scannedSummary.put("brand", scanned.get("brand"));
        scannedSummary.put("category", scanned.get("category"));
        scannedSummary.put("image_url", scanned.get("image_url"));
        scannedSummary.put("serving_size", scanned.get("serving_size"));
        scannedSummary.put("calories", scanned.get("calories"));
        scannedSummary.put("total_sugars", scanned.get("total_sugars"));
        scannedSummary.put("sodium", scanned.get("sodium"));
        scannedSummary.put("total_fat", scanned.get("total_fat"));
        scannedSummary.put("protein", scanned.get("protein"));

        if ("sugar".equals(criteria)) {
            List<Map<String, Object>> cached = getCachedAlternatives(gtin);
            if (cached != null) {
                System.out.println("Recommendations from CACHE for gtin: " + gtin);
                return new RecommendationResult(scannedSummary, cached, criteria);
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

        String scannedCategory = "";
        if (scanned.get("category") != null) {
            scannedCategory = scanned.get("category").toString().toLowerCase().trim();
        }

        boolean categoryFromOFF = scannedCategory.startsWith("en:");
        if (categoryFromOFF) {
            scannedCategory = scannedCategory.substring(3).replace('-', ' ');
        }

        boolean hasUsableCategory = !scannedCategory.isBlank()
                && !GENERIC_CATEGORIES.contains(scannedCategory)
                && !categoryFromOFF;

        String category = "";
        if (scanned.get("category") != null) {
            category = scanned.get("category").toString();
        }

        String categoryHint = categoryToHint(category);
        boolean queryFromCategory = !categoryHint.isBlank();

        String categorySearchQuery;
        if (queryFromCategory) {
            categorySearchQuery = categoryHint;
        } else {
            categorySearchQuery = buildSearchQuery(nameRaw.toString(), category, hasUsableCategory);
        }

        String nameSearchQuery = buildSearchQuery(nameRaw.toString(), category, true);

        System.out.println("Category search query: \""
                + categorySearchQuery + "\" (from: \"" + nameRaw + "\") criteria=" + criteria);
        System.out.println("Name search query: \""
                + nameSearchQuery + "\" (from: \"" + nameRaw + "\") criteria=" + criteria);

        String brandRaw = "";
        if (scanned.get("brand") != null) {
            brandRaw = scanned.get("brand").toString();
        }

        List<Map<String, Object>> brandCandidates = new ArrayList<>();
        if (!brandRaw.isBlank()) {
            String brandQuery = brandRaw + " " + categorySearchQuery;
            System.out.println("Brand search query: \"" + brandQuery + "\"");
            brandCandidates = fetchAllCandidates(brandQuery, gtin);
        }

        List<Map<String, Object>> categoryCandidates = fetchAllCandidates(categorySearchQuery, gtin);

        List<Map<String, Object>> nameCandidates = new ArrayList<>();
        if (!nameSearchQuery.equals(categorySearchQuery)) {
            nameCandidates = fetchAllCandidates(nameSearchQuery, gtin);
        }

        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();

        Map<String, Object> bestBrandCandidate = null;
        for (Map<String, Object> c : brandCandidates) {
            Object raw = c.get(field);
            if (raw == null) {
                continue;
            }
            BigDecimal val = toBigDecimal(raw.toString());
            if (val != null && val.compareTo(scannedValue) < 0) {
                bestBrandCandidate = c;
                break;
            }
        }

        if (bestBrandCandidate != null) {
            String id;
            if (bestBrandCandidate.get("food_id") != null) {
                id = bestBrandCandidate.get("food_id").toString();
            } else {
                id = bestBrandCandidate.get("name").toString();
            }
            seen.put(id, bestBrandCandidate);
        }

        for (Map<String, Object> c : categoryCandidates) {
            String id;
            if (c.get("food_id") != null) {
                id = c.get("food_id").toString();
            } else {
                id = c.get("name").toString();
            }
            seen.putIfAbsent(id, c);
        }

        for (Map<String, Object> c : nameCandidates) {
            String id;
            if (c.get("food_id") != null) {
                id = c.get("food_id").toString();
            } else {
                id = c.get("name").toString();
            }
            seen.putIfAbsent(id, c);
        }

        List<Map<String, Object>> allCandidates = new ArrayList<>(seen.values());

        if (hasUsableCategory) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> c : allCandidates) {
                String cat = "";
                if (c.get("category") != null) {
                    cat = c.get("category").toString().toLowerCase().trim();
                }
                if (cat.equals(scannedCategory) || cat.contains(scannedCategory) || scannedCategory.contains(cat)) {
                    filtered.add(c);
                }
            }
            if (filtered.size() >= MAX_RECOMMENDATIONS) {
                allCandidates = filtered;
            }
        } else {
            List<Map<String, Object>> withoutExcluded = new ArrayList<>();
            for (Map<String, Object> c : allCandidates) {
                String cat = "";
                if (c.get("category") != null) {
                    cat = c.get("category").toString().toLowerCase().trim();
                }
                if (!EXCLUDED_CATEGORIES.contains(cat)) {
                    withoutExcluded.add(c);
                }
            }
            allCandidates = withoutExcluded;

            double[] scannedVec = nutritionVector(scanned);

            allCandidates.sort(Comparator.comparingDouble(c -> {
                double[] candVec = nutritionVector((Map<String, Object>) c);
                return euclideanDistance(scannedVec, candVec);
            }));

            int keep = Math.max(MAX_RECOMMENDATIONS * 4, 30);
            if (allCandidates.size() > keep) {
                allCandidates = new ArrayList<>(allCandidates.subList(0, keep));
            }
        }

        boolean useCombinedScore = !hasUsableCategory;
        double[] scannedVecFinal = null;
        if (useCombinedScore) {
            scannedVecFinal = nutritionVector(scanned);
        }

        final boolean finalUseCombinedScore = useCombinedScore;
        final double[] finalScannedVec = scannedVecFinal;

        List<Map<String, Object>> recommendations = new ArrayList<>();
        int windowSize = Math.min(50, allCandidates.size());

        while (recommendations.size() < MAX_RECOMMENDATIONS && windowSize <= allCandidates.size()) {
            List<Map<String, Object>> window = new ArrayList<>();
            for (Map<String, Object> c : allCandidates.subList(0, windowSize)) {
                Object raw = c.get(field);
                if (raw == null) {
                    continue;
                }
                BigDecimal val = toBigDecimal(raw.toString());
                if (val == null) {
                    continue;
                }
                if (val.compareTo(scannedValue) < 0) {
                    window.add(c);
                }
            }

            window.sort(Comparator.comparingDouble(c -> {
                Object raw = ((Map<String, Object>) c).get(field);
                if (raw == null) {
                    return Double.MAX_VALUE;
                }
                BigDecimal val = toBigDecimal(raw.toString());
                if (val == null) {
                    return Double.MAX_VALUE;
                }
                if (finalUseCombinedScore) {
                    double sugarScore = val.doubleValue();
                    double dist = euclideanDistance(finalScannedVec, nutritionVector((Map<String, Object>) c));
                    return sugarScore + (dist * 1.5);
                }
                return val.doubleValue();
            }));

            recommendations = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_RECOMMENDATIONS, window.size()); i++) {
                recommendations.add(window.get(i));
            }

            if (windowSize == allCandidates.size()) {
                break;
            }
            windowSize = Math.min(windowSize + 20, allCandidates.size());
        }

        recommendations.sort(Comparator.comparing(c -> {
            Object raw = ((Map<String, Object>) c).get(field);
            if (raw == null) {
                return BigDecimal.valueOf(Double.MAX_VALUE);
            }
            BigDecimal val = toBigDecimal(raw.toString());
            if (val != null) {
                return val;
            }
            return BigDecimal.valueOf(Double.MAX_VALUE);
        }));

        if ("sugar".equals(criteria)) {
            String sourceTable = sourceTableName(scanned);
            saveAlternatives(gtin, sourceTable, scannedValue, recommendations);
        }

        System.out.println("Returning " + recommendations.size()
                + " recommendations (" + criteria + ") for gtin: " + gtin);
        return new RecommendationResult(scannedSummary, recommendations, criteria);
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

        String categoryVal = extractFirstSubCategory(
                food.path("food_sub_categories").path("food_sub_category"));

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
        item.put("calories", numNode(serving, "calories"));
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
        item.put("source", "fatsecret");

        return item;
    }

    private void sideCacheProduct(Map<String, Object> product) {
        String foodId = (String) product.get("food_id");
        String gtin = (String) product.get("gtin");
        Map<String, Object> params = toParamMap(product);

        try {
            if (gtin != null && !gtin.isBlank() && foodId != null && !foodId.isBlank()) {
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
                                + "(gtin, food_id, name, brand, category, serving_size, "
                                + " calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                                + " sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, "
                                + " protein, image_url) "
                                + "VALUES (:gtin, :food_id, :name, :brand, :category, :serving_size, "
                                + " :calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                                + " :sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, "
                                + " :protein, :image_url) "
                                + "ON CONFLICT (gtin) DO UPDATE SET "
                                + "  food_id = COALESCE(fatsecret_products.food_id, EXCLUDED.food_id)",
                        params);
            } else if (foodId != null && !foodId.isBlank()) {
                namedDb.update(
                        "INSERT INTO fatsecret_products "
                                + "(food_id, name, brand, category, serving_size, "
                                + " calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                                + " sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, "
                                + " protein, image_url) "
                                + "VALUES (:food_id, :name, :brand, :category, :serving_size, "
                                + " :calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                                + " :sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, "
                                + " :protein, :image_url) "
                                + "ON CONFLICT (food_id) DO NOTHING",
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
        return params;
    }

    private List<Map<String, Object>> getCachedAlternatives(String sourceGtin) {
        try {
            Integer count = db.queryForObject(
                    "SELECT COUNT(*) FROM alternatives WHERE source_gtin = ?",
                    Integer.class, sourceGtin);

            if (count == null || count == 0) {
                return null;
            }

            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT * FROM alternatives WHERE source_gtin = ? ORDER BY rank",
                    sourceGtin);

            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String altFoodId = (String) row.get("alternative_food_id");
                String altGtin = (String) row.get("alternative_gtin");
                String altTableName = (String) row.get("alternative_table");

                Map<String, Object> product = null;

                if ("fatsecret".equals(altTableName)) {
                    if (altFoodId != null && !altFoodId.isBlank()) {
                        List<Map<String, Object>> products = db.queryForList(
                                "SELECT * FROM fatsecret_products WHERE food_id = ?",
                                altFoodId);
                        if (!products.isEmpty()) {
                            product = new LinkedHashMap<>(products.getFirst());
                        }
                    }
                    if (product == null && altGtin != null && !altGtin.isBlank()) {
                        List<Map<String, Object>> products = db.queryForList(
                                "SELECT * FROM fatsecret_products WHERE gtin = ?",
                                altGtin);
                        if (!products.isEmpty()) {
                            product = new LinkedHashMap<>(products.getFirst());
                        }
                    }
                } else if ("openfoodfacts".equals(altTableName) && altGtin != null) {
                    List<Map<String, Object>> products = db.queryForList(
                            "SELECT * FROM openfoodfacts_products WHERE gtin = ?",
                            altGtin);
                    if (!products.isEmpty()) {
                        product = new LinkedHashMap<>(products.getFirst());
                    }
                }

                if (product != null) {
                    product.put("sugar_difference", row.get("sugar_difference"));
                    product.put("sugar_reduction_pct", row.get("sugar_reduction_pct"));
                    product.put("source", altTableName);
                    results.add(product);
                }
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

    private void saveAlternatives(String sourceGtin, String sourceTable,
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
                                + "(source_gtin, source_table, alternative_food_id, "
                                + " alternative_gtin, alternative_table, "
                                + " sugar_difference, sugar_reduction_pct, category_match, rank) "
                                + "VALUES (?, ?, ?, ?, 'fatsecret', ?, ?, ?, ?) "
                                + "ON CONFLICT (source_gtin, alternative_food_id) DO UPDATE SET "
                                + "  alternative_gtin = EXCLUDED.alternative_gtin, "
                                + "  sugar_difference = EXCLUDED.sugar_difference, "
                                + "  sugar_reduction_pct = EXCLUDED.sugar_reduction_pct, "
                                + "  category_match = EXCLUDED.category_match, "
                                + "  rank = EXCLUDED.rank",
                        sourceGtin, sourceTable, altFoodId, altGtin,
                        sugarDiff, reductionPct,
                        alt.get("category"), rank);

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

    private String extractFirstSubCategory(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return null;
            }
            return stringValue(node.get(0));
        }
        return stringValue(node);
    }

    private String sourceTableName(Map<String, Object> product) {
        Object source = product.get("source");
        if ("openfoodfacts".equals(source)) {
            return "openfoodfacts";
        }
        return "fatsecret";
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

    private String categoryToHint(String category) {
        if (category == null || category.isBlank()) {
            return "";
        }
        String slug;
        if (category.contains(":")) {
            slug = category.substring(category.lastIndexOf(':') + 1);
        } else {
            slug = category;
        }
        String hint = slug.replace('-', ' ').trim().toLowerCase();
        if (GENERIC_CATEGORIES.contains(hint)) {
            return "";
        }
        return hint;
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
            sum += diff * diff;
        }
        return Math.sqrt(sum);
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
}