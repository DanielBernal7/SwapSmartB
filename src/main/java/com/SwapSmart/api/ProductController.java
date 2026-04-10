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

// TODO: restrict origins before production
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

    @GetMapping("/products/{gtin}")
    public ResponseEntity<Map<String, Object>> getProduct(@PathVariable String gtin) {
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
    @GetMapping("/food-by-id/{foodId}")
    public ResponseEntity<Map<String, Object>> getFoodById(@PathVariable String foodId) {

        Map<String, Object> food = productService.getDetails(foodId);

        if (food == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Food not found"));
        }

        return ResponseEntity.ok(food);
    }

    @GetMapping("/recommend/{gtin}")
    public ResponseEntity<?> getRecommendations(
            @PathVariable String gtin,
            @RequestParam(defaultValue = "sugar") String criteria) {

        if (gtin == null || gtin.length() < 8 || gtin.length() > 14) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid barcode. Must be 8-14 digits."));
        }

        if (!RecommendationService.VALID_CRITERIA.contains(criteria)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid criteria. Must be one of: "
                            + RecommendationService.VALID_CRITERIA));
        }

        RecommendationService.RecommendationResult result = recommendationService.recommend(gtin, criteria);

        if (result == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Product not found", "gtin", gtin));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scanned", result.scanned());
        response.put("recommendations", result.recommendations());
        response.put("criteria", result.criteria());

        return ResponseEntity.ok(response);
    }
}