package com.SwapSmart.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

//I'm just making a simple check to make sure the it's connect 
// SHould we workign with curl http://localhost:8080/api/health
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api")
public class ProductController {
    private final ProductService productService;
    private final JdbcTemplate db;

    public ProductController(ProductService productService, JdbcTemplate db) {
        this.productService = productService;
        this.db = db;
    }

    // This is just a quici test to make sure the database and the api is working.
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

        System.out.println("Everything is working");
        return ResponseEntity.ok(status);
    }

    // This is the important part.
    @GetMapping("/products/{gtin}")
    public ResponseEntity<Map<String, Object>> getProduct(@PathVariable String gtin) {
        if (gtin == null || gtin.length() < 8 || gtin.length() > 14) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid barcode. Must be 8-14 digits."));
        }

        Map<String, Object> product = productService.lookupProduct(gtin);
        if (product == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Product not found", "gtin", gtin));
        }

        return ResponseEntity.ok(product);
    }

}
