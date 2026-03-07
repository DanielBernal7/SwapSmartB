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

    // This is hte main functions, bascially where general data is called. 
    // The order the methods are called here is important 
    // It needs to be fatsecrets first then the openfoodfacts (unless for whatever reason we want to swtich it)
    // There might actually be a reason when we do the images, but for right now that's not important.
    public Map<String, Object> lookupProduct(String gtin) {
        Map<String, Object> product = findCached("fatsecret_products", gtin);
        if (product != null){
            System.out.println(" This is from the Fat Secret CACHE--------------------------");
            return withSource(product, "fatsecret");
        }

        product = findCached("openfoodfacts_products", gtin);
        if (product != null){
            System.out.println("This is from the Open Food Facts CACHE --------------------------");
            return withSource(product, "openfoodfacts");
        }

        product = fetchFromFatSecret(gtin);
        if (product != null) {
            System.out.println("Fat Secret API ***********\");");
            cacheProduct("fatsecret_products", gtin, product);
            return withSource(product, "fatsecret");
        }

        product = fetchFromOpenFoodFacts(gtin);
        if (product != null) {
            System.out.println("Open Food Facts API ***********");
            cacheProduct("openfoodfacts_products", gtin, product);
            return withSource(product, "openfoodfacts");
        }

        return null;
    }

    // This is the cache. Very important, this is where we see if the cache is in DB 
    // if not then we store it. --------------------------------------------------------
    private Map<String, Object> findCached(String table, String gtin) {
        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM " + table + " WHERE gtin = ?", gtin);
        
        if (rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    private void cacheProduct(String table, String gtin, Map<String, Object> product) {
        product.put("gtin", gtin);

        String cols = "gtin, name, brand, category, serving_size, calories, " +
                "total_fat, saturated_fat, trans_fat, cholesterol, sodium, " +
                "total_carbs, dietary_fiber, total_sugars, added_sugars, protein, raw_json";
        String vals = ":gtin, :name, :brand, :category, :serving_size, :calories, " +
                ":total_fat, :saturated_fat, :trans_fat, :cholesterol, :sodium, " +
                ":total_carbs, :dietary_fiber, :total_sugars, :added_sugars, :protein, CAST(:raw_json AS jsonb)";

        if (table.equals("openfoodfacts_products")) {
            cols += ", nutrition_grade";
            vals += ", :nutrition_grade";
        }

        try {
            namedDb.update(
                    "INSERT INTO " + table + " (" + cols + ") VALUES (" + vals + ") ON CONFLICT (gtin) DO NOTHING",
                    product);
        } catch (Exception e) {
            System.err.println("Cache write failed: " + e.getMessage());
        }
    }

    
    // This is to get from the fat secret API. --------------------------------------------------------
    // (Future me. Remember to either move this into it's own file or section it nicer)
    private Map<String, Object> fetchFromFatSecret(String gtin) {
        try {
            String token = getToken();
            if (token == null)
                return null;

            String body = http.get()
                    .uri(fsApiBase + "/server.api?method=food.find_id_for_barcode.v2&barcode={gtin}&format=json", gtin)
                    .header("Authorization", "Bearer " + token)
                    .retrieve().body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);
            if (root.has("error"))
                return null;

            return parseFatSecret(root, body);
        } catch (Exception e) {
            System.err.println("FatSecret error: " + e.getMessage());
            return null;
        }
    }

    private String getToken() {
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
            fsTokenExpiry = Instant.now().plusSeconds(tokenJson.get("expires_in").asInt() - 60);
            return fsToken;
        } catch (Exception e) {
            System.err.println("FatSecret token error: " + e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseFatSecret(JsonNode root, String rawJson) {
        JsonNode food = root.path("food");
        JsonNode servings = food.path("servings").path("serving");
        JsonNode subCats = food.path("food_sub_categories").path("food_sub_category");

        JsonNode serving;
        if (servings.isArray()) {
            serving = servings.get(0);
        } else {
            serving = servings;
        }

        String category = null;
        if (subCats.isArray() && !subCats.isEmpty()) {
            category = subCats.get(0).asString(null);
        }

        Map<String, Object> product = new HashMap<>();
        product.put("name", food.path("food_name").asString(""));
        product.put("brand", food.path("brand_name").asString(null));
        product.put("category", category);
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
        product.put("raw_json", rawJson);
        return product;
    }

    // This is bascially the same idea as the one up top but less messy.

    private Map<String, Object> fetchFromOpenFoodFacts(String gtin) {
        try {
            String body = http.get()
                    .uri(offApiBase + "/{gtin}.json", gtin)
                    .header("User-Agent", offUserAgent)
                    .retrieve().body(String.class);

            JsonNode root = JsonMapper.shared().readTree(body);
            if (root.path("status").asInt(0) != 1)
                return null;

            return parseOpenFoodFacts(root, body);
        } catch (Exception e) {
            System.err.println("OFF error: " + e.getMessage());
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

    // These are just helper methods. Basically their whole puprose is to extract a numberic value from JSON 
    // without anything weird happeining. If not valid or something is missing then we just give null.

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
            if (value != null)
                return value;
        }
        return null;
    }

    // This is for testing. I used it for testing to see which source was providing the data. 
    private Map<String, Object> withSource(Map<String, Object> product, String source) {
        product.put("source", source);
        return product;
    }
}