package com.SwapSmart.api;

import java.util.Map;
import java.util.LinkedHashMap;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

// This is left like this for testing but should be restricted later on
// It just allows any request from any domain 
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api")
public class ProductController {
    private final ProductService productService;
    private final JdbcTemplate db;
    private final RecommendationService recommendationService;

    public ProductController(ProductService productService, JdbcTemplate db,
            RecommendationService recommendationService) {
        this.productService = productService;
        this.db = db;
        this.recommendationService = recommendationService;
    }

    // Quick check to make sure the database and server are working
    // Used mainly for testing
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("server", "running");

        try {
            db.queryForObject("SELECT 1", Integer.class);
            status.put("database", "connected");
        } catch (Exception e) {
            status.put("database", "error: " + e.getMessage());
            return ResponseEntity.status(503).body(status);
        }

        return ResponseEntity.ok(status);
    }

    // This is to look up a single product.
    // This looks up all nutritoin data of that product from a barcode (GTIN)
    // This should be called like curl
    // http://localhost:8080/api/products/016000437791
    @GetMapping("/products/{gtin}")
    public ResponseEntity<Map<String, Object>> getProduct(@PathVariable String gtin) {
        // This is basic bardocde validation, it can be modified if we later have
        // issues.
        if (gtin == null || gtin.length() < 8 || gtin.length() > 14) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid barcode. Must be 8-14 digits."));
        }

        Map<String, Object> product = productService.lookupProduct(gtin);

        if (product == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Product not found", "gtin", gtin));
        }

        return ResponseEntity.ok(product);
    }

    /*
     * This is the recomend system. This is a bit complex so I'll comment it a bit
     * more
     * 
     * Get /api/recommend/{gtin}?criteria=sugar (sugar is default so if not placed
     * it defaults to recommending less sugar)
     * 
     * This returns a "healthier" alternative for the scanned product. Depeneding on
     * the critera this could look different.
     * The sugar is the only comprhensive one I wrote
     * 
     * The reponse it gives should then fall into three fields:
     * "scanned": { the product that was scanned with it's nutrition info },
     * "recommendations:" [ up to 5 lower sugar (or other ) alternatives ],
     * "critera": "sugar" which is what we sorted by. I don't remember if I actually
     * implemented this
     * 
     * possible parameters
     * Query parameters:
     * ?critera=sugar (default)
     * ?criteria=sodium (not tested or comprehensive)
     * ?criteria=calories (not tested or comprhensive)
     * 
     * Examples:
     * curl http://localhost:8080/api/recommend/016000437791
     * curl "http://localhost:8080/api/recommend/028400201322?criteria=sodium"
     * curl "http://localhost:8080/api/recommend/016000437791?criteria=calories"
     * 
     * 
     * It should then return one of three status codes:
     * 200 = success (recomenation may also be empty if the product already has 0
     * sugar/other or can't be better)
     * 400 = invalid barcode or invalid critera
     * 404 = product not found in any of the data source (neither in cache or api
     * calls )
     * 
     */
    @GetMapping("/recommend/{gtin}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String gtin,
            @RequestParam(defaultValue = "sugar") String criteria) {

        // Validate barcode (I should made this a method instead, oh well.)
        if (gtin == null || gtin.length() < 8 || gtin.length() > 14) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid barcode. Must be 8-14 digits."));
        }

        // Validate that the criteria is one we support. (this is defined in the recomendation serivce class)
        // VALID_CRITERIA is a Set containing: "sugar", "sodium", "calories"
        if (!RecommendationService.VALID_CRITERIA.contains(criteria)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid criteria. Must be one of: "
                            + RecommendationService.VALID_CRITERIA));
        }

        // Delegate to RecommendationService which does the heavy lifting:
        // cache check -> product lookup -> FatSecret search -> filter -> rank -> cache
        // results
        RecommendationService.RecommendationResult result = recommendationService.recommend(gtin, criteria);

        // null means the product itself wasn't found anywhere. Again this probably should be method instead
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Product not found", "gtin", gtin));
        }

        // This is to build the resposne to get in the frontend
        // LinkedHashMap preserves insertion order, so JSON keys appear in this order.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanned", result.scanned()); // The product that was scanned
        response.put("recommendations", result.recommendations()); // Up to 5 alternatives (may be empty)
        response.put("criteria", result.criteria()); // What we filtered/sorted by

        return ResponseEntity.ok(response);
    }
}