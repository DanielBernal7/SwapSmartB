package com.SwapSmart.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

@Service
public class UsdaService {
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate namedDb;
    private final RestClient http;

    @Value("${usda.api-key}")
    private String usdaApiKey;
    @Value("${usda.api-base-url}")
    private String usdaApiBase;

    private static final Map<String, String> CATEGORY_TO_TYPE = new LinkedHashMap<>();
    static {
        CATEGORY_TO_TYPE.put("Sports Drinks", "sports drink");
        CATEGORY_TO_TYPE.put("Energy Drinks", "energy drink");
        CATEGORY_TO_TYPE.put("Carbonated Soft Drinks", "soda");
        CATEGORY_TO_TYPE.put("Non-Carbonated Soft Drinks", "sports drink");
        CATEGORY_TO_TYPE.put("Fruit Juices & Nectars", "fruit juice");
        CATEGORY_TO_TYPE.put("Fruit Drinks", "fruit drink");
        CATEGORY_TO_TYPE.put("Granola Bars", "granola bar");
        CATEGORY_TO_TYPE.put("Protein Bars", "protein bar");
        CATEGORY_TO_TYPE.put("Nutrition Bars", "nutrition bar");
        CATEGORY_TO_TYPE.put("Snack/Meal Bars", "nutrition bar");
        CATEGORY_TO_TYPE.put("Cereal Bars", "cereal bar");
        CATEGORY_TO_TYPE.put("Fruit Snacks", "fruit snack");
        CATEGORY_TO_TYPE.put("Ready-To-Eat Cereals", "breakfast cereal");
        CATEGORY_TO_TYPE.put("Hot Cereals", "oatmeal");
        CATEGORY_TO_TYPE.put("Oatmeal", "oatmeal");
        CATEGORY_TO_TYPE.put("Yogurt", "yogurt");
        CATEGORY_TO_TYPE.put("Yogurt Snacks", "yogurt snack");
        CATEGORY_TO_TYPE.put("Greek Yogurt", "greek yogurt");
        CATEGORY_TO_TYPE.put("Ice Cream & Frozen Desserts", "ice cream");
        CATEGORY_TO_TYPE.put("Frozen Yogurt", "frozen yogurt");
        CATEGORY_TO_TYPE.put("Potato Chips, Pretzels & Snacks", "chips");
        CATEGORY_TO_TYPE.put("Potato Chips", "potato chips");
        CATEGORY_TO_TYPE.put("Corn Chips & Tortilla Chips", "tortilla chips");
        CATEGORY_TO_TYPE.put("Crackers", "crackers");
        CATEGORY_TO_TYPE.put("Cookies & Biscuits", "cookies");
        CATEGORY_TO_TYPE.put("Candy", "candy");
        CATEGORY_TO_TYPE.put("Chocolate Candy", "chocolate");
        CATEGORY_TO_TYPE.put("Gum & Mints", "gum");
        CATEGORY_TO_TYPE.put("Peanut Butter & Other Nut Butters", "peanut butter");
        CATEGORY_TO_TYPE.put("Nuts & Seeds", "nuts");
        CATEGORY_TO_TYPE.put("Trail Mix", "trail mix");
        CATEGORY_TO_TYPE.put("Popcorn, Pork Rinds & Snacks", "popcorn");
        CATEGORY_TO_TYPE.put("Rice Cakes", "rice cakes");
        CATEGORY_TO_TYPE.put("Pretzels", "pretzels");
        CATEGORY_TO_TYPE.put("Milk", "milk");
        CATEGORY_TO_TYPE.put("Flavored Milk", "flavored milk");
        CATEGORY_TO_TYPE.put("Plant-Based Milk", "plant milk");
        CATEGORY_TO_TYPE.put("Coffee & Tea", "coffee");
        CATEGORY_TO_TYPE.put("Waters", "water");
        CATEGORY_TO_TYPE.put("Sparkling Water", "sparkling water");
        CATEGORY_TO_TYPE.put("Salad Dressings", "salad dressing");
        CATEGORY_TO_TYPE.put("Condiments", "condiment");
        CATEGORY_TO_TYPE.put("Ketchup", "ketchup");
        CATEGORY_TO_TYPE.put("Hot Sauce", "hot sauce");
        CATEGORY_TO_TYPE.put("Soups, Broths & Bouillon", "soup");
        CATEGORY_TO_TYPE.put("Bread", "bread");
        CATEGORY_TO_TYPE.put("Tortillas & Wraps", "tortilla");
        CATEGORY_TO_TYPE.put("Bagels & English Muffins", "bagel");
        CATEGORY_TO_TYPE.put("Pasta", "pasta");
        CATEGORY_TO_TYPE.put("Frozen Meals", "frozen meal");
        CATEGORY_TO_TYPE.put("Pizza", "pizza");
        CATEGORY_TO_TYPE.put("Jerky & Meat Snacks", "jerky");
        CATEGORY_TO_TYPE.put("Ice Cream Cones", "ice cream");
        CATEGORY_TO_TYPE.put("Frozen Novelties", "frozen novelty");
        CATEGORY_TO_TYPE.put("Fruit Punch", "fruit punch");
        CATEGORY_TO_TYPE.put("Drink Mixes", "drink mix");
        CATEGORY_TO_TYPE.put("Nutrition Drinks", "nutrition drink");
        CATEGORY_TO_TYPE.put("Water", "water");
        CATEGORY_TO_TYPE.put("Candy & Sweets", "candy");
        CATEGORY_TO_TYPE.put("Snack Foods", "snack");
        CATEGORY_TO_TYPE.put("Sports Drinks", "sports drink");
    }

    private static final Map<String, String> NUTRIENT_TO_COLUMN = new LinkedHashMap<>();
    static {
        NUTRIENT_TO_COLUMN.put("Energy", "calories");
        NUTRIENT_TO_COLUMN.put("Total lipid (fat)", "total_fat");
        NUTRIENT_TO_COLUMN.put("Fatty acids, total saturated", "saturated_fat");
        NUTRIENT_TO_COLUMN.put("Fatty acids, total trans", "trans_fat");
        NUTRIENT_TO_COLUMN.put("Cholesterol", "cholesterol");
        NUTRIENT_TO_COLUMN.put("Sodium", "sodium");
        NUTRIENT_TO_COLUMN.put("Carbohydrate, by difference", "total_carbs");
        NUTRIENT_TO_COLUMN.put("Fiber, total dietary", "dietary_fiber");
        NUTRIENT_TO_COLUMN.put("Sugars, total including NLEA", "total_sugars");
        NUTRIENT_TO_COLUMN.put("Sugars, added", "added_sugars");
        NUTRIENT_TO_COLUMN.put("Protein", "protein");
    }

    public UsdaService(JdbcTemplate db) {
        this.db = db;
        this.namedDb = new NamedParameterJdbcTemplate(db);
        this.http = RestClient.create();
    }

    public static String deriveProductType(String... categories) {
        String firstNonBlank = null;
        for (String cat : categories) {
            if (cat == null || cat.isBlank()) {
                continue;
            }
            if (firstNonBlank == null) {
                firstNonBlank = cat;
            }

            String mapped = CATEGORY_TO_TYPE.get(cat.trim());
            if (mapped != null) {
                return mapped;
            }

            for (Map.Entry<String, String> entry : CATEGORY_TO_TYPE.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(cat.trim())) {
                    return entry.getValue();
                }
            }
        }
        if (firstNonBlank != null) {
            String lower = firstNonBlank.toLowerCase().trim();
            if (!lower.equals("snacks") && !lower.equals("foods") && !lower.equals("other")
                    && !lower.equals("misc") && !lower.equals("general")
                    && !lower.equals("beverages")) {
                return lower;
            }
        }
        return null;
    }

    public Map<String, Object> lookupByGtin(String gtin) {
        if (gtin == null || gtin.isBlank()) {
            return null;
        }

        List<Map<String, Object>> rows = db.queryForList(
                "SELECT * FROM usda_products WHERE gtin = ?", gtin);
        if (!rows.isEmpty()) {
            System.out.println("USDA CACHE");
            return rows.getFirst();
        }

        Map<String, Object> product = fetchFromUsda(gtin);
        if (product != null) {
            System.out.println("USDA API");
            cacheUsdaProduct(gtin, product);
        }
        return product;
    }

    public void enrichFatSecretProduct(String gtin, String foodId, String fsCategory) {
        if (gtin == null || gtin.isBlank()) {
            if (foodId != null && !foodId.isBlank() && fsCategory != null) {
                String pt = deriveProductType(fsCategory);
                if (pt != null) {
                    db.update(
                            "UPDATE fatsecret_products SET product_type = ? "
                                    + "WHERE food_id = ? AND product_type IS NULL",
                            pt, foodId);
                }
            }
            return;
        }

        List<Map<String, Object>> check = db.queryForList(
                "SELECT product_type FROM fatsecret_products WHERE gtin = ?", gtin);
        if (!check.isEmpty() && check.getFirst().get("product_type") != null) {
            return;
        }

        Map<String, Object> usda = lookupByGtin(gtin);
        String usdaCategory;
        if (usda != null) {
            usdaCategory = (String) usda.get("category");
        } else {
            usdaCategory = null;
        }
        String productType = deriveProductType(usdaCategory, fsCategory);

        if (productType != null || usdaCategory != null) {
            db.update(
                    "UPDATE fatsecret_products SET product_type = ?, usda_category = ? "
                            + "WHERE gtin = ?",
                    productType, usdaCategory, gtin);
        }
    }

    private Map<String, Object> fetchFromUsda(String gtin) {
        try {
            String body = http.get()
                    .uri(usdaApiBase + "/foods/search"
                            + "?query={gtin}&dataType=Branded&pageSize=5&api_key={key}",
                            gtin, usdaApiKey)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                return null;
            }

            JsonNode root = JsonMapper.shared().readTree(body);
            JsonNode foods = root.path("foods");
            if (!foods.isArray() || foods.isEmpty()) {
                return null;
            }

            Set<String> gtinVariants = buildGtinVariants(gtin);

            for (JsonNode food : foods) {
                String upc = food.path("gtinUpc").asString(null);
                if (upc != null && gtinVariants.contains(upc)) {
                    return parseUsda(food, gtin);
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("USDA API error for gtin=" + gtin + ": " + e.getMessage());
            return null;
        }
    }

    private Set<String> buildGtinVariants(String gtin) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(gtin);
        try {
            long numeric = Long.parseLong(gtin);
            variants.add(String.format("%012d", numeric));
            variants.add(String.format("%013d", numeric));
            variants.add(String.format("%014d", numeric));
        } catch (NumberFormatException ignored) {
        }
        return variants;
    }

    private Map<String, Object> parseUsda(JsonNode food, String gtin) {
        String name = food.path("description").asString(null);
        if (name == null || name.isBlank()) {
            return null;
        }

        Map<String, Object> product = new LinkedHashMap<>();
        product.put("gtin", gtin);
        product.put("fdc_id", String.valueOf(food.path("fdcId").asInt(0)));
        product.put("name", name);
        product.put("brand", food.path("brandOwner").asString(null));

        String category = food.path("brandedFoodCategory").asString(null);
        product.put("category", category);

        String householdServing = food.path("householdServingFullText").asString(null);
        if (householdServing != null && !householdServing.isBlank()) {
            product.put("serving_size", householdServing);
        } else {
            double sz = food.path("servingSize").asDouble(0);
            String unit = food.path("servingSizeUnit").asString("");
            if (sz > 0) {
                product.put("serving_size", sz + " " + unit);
            } else {
                product.put("serving_size", null);
            }
        }

        Map<String, BigDecimal> nutrition = new HashMap<>();
        JsonNode nutrients = food.path("foodNutrients");
        if (nutrients.isArray()) {
            for (JsonNode n : nutrients) {
                String nutrientName = n.path("nutrientName").asString(null);
                String col;
                if (nutrientName != null) {
                    col = NUTRIENT_TO_COLUMN.get(nutrientName);
                } else {
                    col = null;
                }
                if (col != null) {
                    try {
                        nutrition.put(col, new BigDecimal(String.valueOf(n.path("value").asDouble())));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }

        product.put("calories", nutrition.get("calories"));
        product.put("total_fat", nutrition.get("total_fat"));
        product.put("saturated_fat", nutrition.get("saturated_fat"));
        product.put("trans_fat", nutrition.get("trans_fat"));
        product.put("cholesterol", nutrition.get("cholesterol"));
        product.put("sodium", nutrition.get("sodium"));
        product.put("total_carbs", nutrition.get("total_carbs"));
        product.put("dietary_fiber", nutrition.get("dietary_fiber"));
        product.put("total_sugars", nutrition.get("total_sugars"));
        product.put("added_sugars", nutrition.get("added_sugars"));
        product.put("protein", nutrition.get("protein"));

        return product;
    }

    private void cacheUsdaProduct(String gtin, Map<String, Object> product) {
        try {
            namedDb.update(
                    "INSERT INTO usda_products "
                            + "(gtin, fdc_id, name, brand, category, serving_size, "
                            + " calories, total_fat, saturated_fat, trans_fat, cholesterol, "
                            + " sodium, total_carbs, dietary_fiber, total_sugars, added_sugars, protein) "
                            + "VALUES (:gtin, :fdc_id, :name, :brand, :category, :serving_size, "
                            + " :calories, :total_fat, :saturated_fat, :trans_fat, :cholesterol, "
                            + " :sodium, :total_carbs, :dietary_fiber, :total_sugars, :added_sugars, :protein) "
                            + "ON CONFLICT (gtin) DO NOTHING",
                    product);
        } catch (Exception e) {
            System.err.println("USDA cache write failed for gtin=" + gtin + ": " + e.getMessage());
        }
    }
}
