package com.SwapSmart.api.controller;

import com.SwapSmart.api.ProductService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final ProductService productService;

    public SearchController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping(value = "/search", produces = "application/json")
    public List<Map<String, Object>> search(
        @RequestParam String query,
        @RequestParam(required = false) String category) {
        return productService.searchProducts(query, category);
    }

    @GetMapping(value = "/categories", produces = "application/json")
    public List<Map<String, Object>> getCategories() {
        return productService.getFoodCategories();
    }
}