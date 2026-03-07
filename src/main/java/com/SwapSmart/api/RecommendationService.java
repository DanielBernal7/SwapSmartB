// This is the brain of SwapSmart. Given a scanned product, it finds
// similar but healthier alternatives by searching FatSecret.

// The basic flow:
//   1. Look up what the user scanned
//   2. Search FatSecret for similar products ("granola" -> 100 granola results)
//   3. Cache everything in our DB (so we're not constantly calling the API)
//   4. Filter down to items that are lower in sugar/sodium/calories
//   5. Return the top 5
package com.SwapSmart.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

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

    // How many alternatives to show the user (we can change this, but I think 5 is a good number)
    private static final int MAX_RECOMMENDATIONS = 5;

    // FatSecret allows up to 50 per page, so we use this the max to minimize API calls
    private static final int PAGE_SIZE = 50;

    // We grab 2 pages max (100 candidates)
    private static final int MAX_PAGES = 2;

    // The criteria the frontend can ask for (can be changed later )
    public static final Set<String> VALID_CRITERIA = Set.of(
            "sugar", "sodium", "calories");

    // Maps the criteria name to the actual field in our product data.
    // So when the frontend says ?criteria=sugar, we know to compare "total_sugars".
    private static final Map<String, String> CRITERIA_FIELDS = Map.of(
            "sugar", "total_sugars",
            "sodium", "sodium",
            "calories", "calories");

    
    public record RecommendationResult(
            Map<String, Object> scanned,
            List<Map<String, Object>> recommendations,
            String criteria) {
    }

    // Words that don't describe the food type we strip these to get
    // cleaner search queries.
    // e.g. "Nature Valley Crunchy Granola Bars Oats & Honey" -> "granola bars"
    // I found this list online, but I don't remember where. 
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

    // Multiword food type phrases. If the product name contains one of these
    // we use it directly as the search query. Ordered longest-first so
    // "granola bar" matches before just "bar".
    // Similar things as above, I foung this online. It was meant for python
    // but works here as well. 
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

    public RecommendationService(ProductService productService, JdbcTemplate db) {
        this.productService = productService;
        this.db = db;
        this.namedDb = new NamedParameterJdbcTemplate(db);
        this.http = RestClient.create();
    }

    // Public API this is what the controller calls

    // Returns up to 5 alternatives that are lower in whatever criteria
    // was requested (sugar, sodium, or calories). Returns null if the
    // product doesn't exist, or an empty list if nothing beats it.
    public RecommendationResult recommend(String gtin, String criteria) {
        // Figure out which field to compare
        // e.g. criteria="sugar" -> field="total_sugars"
        String field = CRITERIA_FIELDS.getOrDefault(criteria, "total_sugars");

        // 1. Look up the scanned product
        Map<String, Object> scanned = productService.lookupProduct(gtin);
        if (scanned == null) {
            return null;
        }

        // Build a summary of the scanned product for the response.
        // We don't send everything (like raw_json), just the useful stuff.
        // This could be modified depending on what is needed
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

        // 2. Check if we already have cached results (only for sugar right now
        // since that's what gets saved to the alternatives table)
        if ("sugar".equals(criteria)) {
            List<Map<String, Object>> cached = getCachedAlternatives(gtin);
            if (cached != null) {
                System.out.println("Recommendations from CACHE for gtin: " + gtin);
                return new RecommendationResult(scannedSummary, cached, criteria);
            }
        }

        // 3. Make sure the product actually has data for this criteria
        Object valueRaw = scanned.get(field);
        if (valueRaw == null) {
            return new RecommendationResult(scannedSummary, Collections.emptyList(), criteria);
        }

        BigDecimal scannedValue = toBigDecimal(valueRaw.toString());
        if (scannedValue == null || scannedValue.compareTo(BigDecimal.ZERO) <= 0) {
            // Already at 0 it can't go lower or at least it shoudn't...
            return new RecommendationResult(scannedSummary, Collections.emptyList(), criteria);
        }

        Object nameRaw = scanned.get("name");
        if (nameRaw == null || nameRaw.toString().isBlank()) {
            return new RecommendationResult(scannedSummary, Collections.emptyList(), criteria);
        }

        // 4. Build a search query from the product name
        // "Nature Valley Crunchy Granola Bars Oats & Honey" -> "granola bars"
        // I keep referencning this product becasue It's what I was using to test
        String searchQuery = buildSearchQuery(nameRaw.toString());
        System.out.println("Recommendation search query: \""
                + searchQuery + "\" (from: \"" + nameRaw + "\") criteria=" + criteria);

        // 5. Search FatSecret for similar products (caches everything along the way)
        List<Map<String, Object>> allCandidates = fetchAllCandidates(searchQuery, gtin);

        // 6. Filter to items that are actually lower, then sort and take top 5
        // This uses Java streams which is basically:
        // take the list -> keep only matching items -> sort them -> take first 5 -> make a
        // new list. I've only ever used this once before, I have zero idea why I chose to use it again,
        // I'll proabably avoid using it again though.
        List<Map<String, Object>> recommendations = allCandidates.stream()
                .filter(c -> {
                    Object raw = c.get(field);
                    if (raw == null) {
                        return false;
                    }
                    BigDecimal val = toBigDecimal(raw.toString());
                    if (val == null) {
                        return false;
                    }
                    return val.compareTo(scannedValue) < 0;
                })
                .sorted(Comparator.comparing(c -> {
                    Object raw = c.get(field);
                    if (raw == null) {
                        return BigDecimal.valueOf(Double.MAX_VALUE);
                    }
                    BigDecimal val = toBigDecimal(raw.toString());
                    if (val != null) {
                        return val;
                    }
                    return BigDecimal.valueOf(Double.MAX_VALUE);
                }))
                .limit(MAX_RECOMMENDATIONS)
                .collect(Collectors.toList());

        // 7. Save sugar results to the alternatives table for next time.
        // Sodium and calorie rankings are computed on the fly since the search
        // data is already cached, no need to save multiple rankings.
        if ("sugar".equals(criteria)) {
            String sourceTable = sourceTableName(scanned);
            saveAlternatives(gtin, sourceTable, scannedValue, recommendations);
        }

        System.out.println("Returning " + recommendations.size()
                + " recommendations (" + criteria + ") for gtin: " + gtin);
        return new RecommendationResult(scannedSummary, recommendations, criteria);
    }

    // Paginated FatSecret search
    //
    // FatSecret returns results in pages (up to 50 per page).
    // We fetch 2 pages = 100 candidates max, and cache all of them in our DB.

    private List<Map<String, Object>> fetchAllCandidates(String query, String excludeGtin) {
        List<Map<String, Object>> allCandidates = new ArrayList<>();
        int totalResults = Integer.MAX_VALUE; // gets set after page 0

        for (int page = 0; page < MAX_PAGES; page++) {
            // If page 0 already got everything, skip page 1
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
                // Save to our DB for future barcode lookups
                sideCacheProduct(food);

                // Don't recommend the scanned product back to the user
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

    // Fetches a single page of search results from FatSecret
    private SearchPage fetchPage(String query, int pageNumber) {
        try {
            String token = productService.getToken();

            // {query}, {page}, {maxResults} get substituted into the URL by Spring
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

    // Just bundles the total count with the food list for a page
    private record SearchPage(int totalResults, List<Map<String, Object>> foods) {
    }

    // Parsing search results
    // Important: foods.search.v5 does NOT return food_barcode for most items.
    // We use food_id as the primary key for search results.
    // This was causing problems, but I think I've fixed them

    private Map<String, Object> parseFoodResult(JsonNode food) {
        // Find the best serving, prefer the default, fall back to first
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
            // Single serving not wrapped in an array
            serving = servingsNode;
        }

        if (serving == null) {
            return null;
        }

        // Skip items with no sugar data, can't compare them
        BigDecimal sugar = numNode(serving, "sugar");
        if (sugar == null) {
            return null;
        }

        // Get the category (can be array or single string in FatSecret's response)
        String category = extractFirstSubCategory(
                food.path("food_sub_categories").path("food_sub_category"));

        // Grab first image if there is one (again something we will later have to deal with.)
        String imageUrl = null;
        JsonNode images = food.path("food_images").path("food_image");
        if (images.isArray() && !images.isEmpty()) {
            imageUrl = stringValue(images.get(0).path("image_url"));
        }

        // food_barcode is usually missing from search results, that's fine,
        // we have food_id which is always there
        String gtin = stringValue(food.path("food_barcode"));
        String foodId = stringValue(food.path("food_id"));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("gtin", gtin);
        item.put("food_id", foodId);
        item.put("name", stringValueOrEmpty(food.path("food_name")));
        item.put("brand", stringValue(food.path("brand_name")));
        item.put("food_type", stringValue(food.path("food_type")));
        item.put("category", category);
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

    // Side caching search results into fatsecret_products
    // When we search for "granola" we get ~100 products. We save ALL of them
    // so that if someone scans one by barcode later, it's already in our DB.
    // The logic: if a product was already cached (from a previous search
    // or barcode scan), we merge instead of creating a duplicate row.

    private void sideCacheProduct(Map<String, Object> product) {
        String foodId = (String) product.get("food_id");
        String gtin = (String) product.get("gtin");
        Map<String, Object> params = toParamMap(product);

        try {
            // If we have both gtin and food_id, try to merge into an existing row
            // that was cached by food_id but doesn't have a barcode yet
            if (gtin != null && !gtin.isBlank()
                    && foodId != null && !foodId.isBlank()) {
                int updated = namedDb.update(
                        "UPDATE fatsecret_products SET gtin = :gtin "
                                + "WHERE food_id = :food_id AND gtin IS NULL",
                        params);
                if (updated > 0) {
                    return; // merged, we're done
                }
            }

            if (gtin != null && !gtin.isBlank()) {
                // Has a barcode then we insert by gtin, backfill food_id if we have it
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
                // No barcode (typical for search results) then we insert by food_id
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

    // Picks the best ID for storing in the alternatives table.
    // food_id is preferred since search results always have it.
    private String alternativeKey(Map<String, Object> product) {
        String foodId = (String) product.get("food_id");
        if (foodId != null && !foodId.isBlank()) {
            return foodId;
        }
        String gtin = (String) product.get("gtin");
        if (gtin != null && !gtin.isBlank()) {
            return gtin;
        }
        return null;
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

    // Alternatives cache, this is precomputed recommendations table
    // After the first search for a product, we save the top 5 results to
    // the alternatives table. Next time someone scans the same product,
    // we just read from the table instead of re-searching FatSecret.

    // Returns cached recommendations, or null if we haven't computed them yet.
    // Also "re-hydrates" the full product data by joining back to
    // fatsecret_products.
    private List<Map<String, Object>> getCachedAlternatives(String sourceGtin) {
        try {
            Integer count = db.queryForObject(
                    "SELECT COUNT(*) FROM alternatives WHERE source_gtin = ?",
                    Integer.class, sourceGtin);

            if (count == null || count == 0) {
                return null; // cache miss, this means we need to search
            }

            List<Map<String, Object>> rows = db.queryForList(
                    "SELECT * FROM alternatives WHERE source_gtin = ? ORDER BY rank",
                    sourceGtin);

            // The alternatives table only stores IDs and the sugar diff.
            // We need to look up the full product data for each one.
            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String altFoodId = (String) row.get("alternative_food_id");
                String altGtin = (String) row.get("alternative_gtin");
                String altTableName = (String) row.get("alternative_table");

                Map<String, Object> product = null;

                if ("fatsecret".equals(altTableName)) {
                    // Try food_id first (most reliable for search results)
                    if (altFoodId != null && !altFoodId.isBlank()) {
                        List<Map<String, Object>> products = db.queryForList(
                                "SELECT * FROM fatsecret_products WHERE food_id = ?",
                                altFoodId);
                        if (!products.isEmpty()) {
                            product = new LinkedHashMap<>(products.getFirst());
                        }
                    }
                    // Fall back to gtin lookup
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
                    // Add the pre-computed comparison fields
                    product.put("sugar_difference", row.get("sugar_difference"));
                    product.put("sugar_reduction_pct", row.get("sugar_reduction_pct"));
                    product.put("source", altTableName);
                    results.add(product);
                }
            }

            // If all the product rows got deleted somehow, treat it as a cache miss
            if (results.isEmpty()) {
                return null;
            }

            return results;

        } catch (Exception e) {
            System.err.println("Alternatives cache read failed: " + e.getMessage());
            return null;
        }
    }

    // Saves the top recommendations so we don't have to re-search next time.
    // Also pre-computes sugar_difference and sugar_reduction_pct so the
    // frontend can say "12g less sugar (75% reduction)" without doing math.
    private void saveAlternatives(String sourceGtin, String sourceTable,
            BigDecimal scannedSugar,
            List<Map<String, Object>> recommendations) {
        int rank = 0;
        for (Map<String, Object> alt : recommendations) {
            rank++;

            String altFoodId = (String) alt.get("food_id");
            String altGtin = (String) alt.get("gtin");

            if (altFoodId == null || altFoodId.isBlank()) {
                System.err.println("Skipping alternative with no food_id: "
                        + alt.get("name"));
                continue;
            }

            try {
                // Calculate how much less sugar this alternative has
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
                System.err.println("Alternatives save failed for food_id="
                        + altFoodId + ": " + e.getMessage());
            }
        }
    }

    // Search query building
    // Takes a product name and strips it down to just the food type.
    // This is important because searching "Nature Valley Crunchy Granola Bars
    // Oats & Honey" won't find other granola bars — too specific.
    // We want just "granola bars".

    String buildSearchQuery(String productName) {
        String lower = productName.toLowerCase();

        // For the first try we check for known food phrases (longest match wins)
        for (String phrase : FOOD_TYPE_PHRASES) {
            if (lower.contains(phrase)) {
                return phrase;
            }
        }

        // For the second try we strip out noise words and keep up to 3 real words
        String[] tokens = lower.replaceAll("[^a-z0-9 ]", " ").split("\\s+");
        List<String> kept = Arrays.stream(tokens)
                .filter(t -> t.length() > 2) // skip tiny words
                .filter(t -> !NOISE_WORDS.contains(t)) // skip brand/flavor words
                .filter(t -> !t.matches("\\d+")) // skip numbers like "12"
                .limit(3)
                .collect(Collectors.toList());

        if (!kept.isEmpty()) {
            return String.join(" ", kept.subList(0, Math.min(3, kept.size())));
        }

        //for the last resort we just use the first two words of the name
        String[] raw = productName.trim().split("\\s+");
        return String.join(" ", Arrays.copyOf(raw, Math.min(2, raw.length)));
    }

    // Helpers, we mostly just safe value extraction from JSON

    // food_sub_category can be an array or a single string in FatSecret's API.
    // We always want the first one.
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

    // Returns "fatsecret" or "openfoodfacts" this needs to match the CHECK
    // constraint on the alternatives table (wait, now that I think about it, did I actually set it...)
    // Future me come back here later after you ccheck it. 
    private String sourceTableName(Map<String, Object> product) {
        Object source = product.get("source");
        if ("openfoodfacts".equals(source)) {
            return "openfoodfacts";
        }
        return "fatsecret";
    }

    // Pulls a number out of a JSON field. Returns null if it's missing or weird.
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

    // Gets the string value of a JSON node. Returns null if missing.
    // Have to check isNull() first because stringValue() throws on NullNode.
    private String stringValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.stringValue();
    }

    // Same as stringValue() but returns "" instead of null.
    // Used for required fields like name where null would be confusing.
    private String stringValueOrEmpty(JsonNode node) {
        String value = stringValue(node);
        if (value != null) {
            return value;
        }
        return "";
    }

    // Parses a string into BigDecimal. Returns null if it can't.
    // We use BigDecimal instead of double because floating point is imprecise
    // and we don't want rounding errors messing up our ranking.
    // Is this absolutly necessary. Probably not, but just prevents issues later on.
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
}