package com.SwapSmart.api.controller;

import com.SwapSmart.api.dto.FoodDetailDTO;
import com.SwapSmart.api.dto.FatSecretSearchResponse;
import com.SwapSmart.api.dto.SearchResultDTO;
import com.SwapSmart.api.service.FatSecretService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class SearchController {

    private final FatSecretService fatSecretService;

    public SearchController(FatSecretService fatSecretService) {
        this.fatSecretService = fatSecretService;
    }

    @GetMapping(value = "/api/search", produces = "application/json")
    public List<SearchResultDTO> search(@RequestParam String query) throws Exception {

        FatSecretSearchResponse response = fatSecretService.searchFoods(query);

        return response.food.stream()
                .map(food -> new SearchResultDTO(
                        food.foodId,
                        food.foodName,
                        food.brandName,
                        food.description
                ))
                .collect(Collectors.toList());
    }

    @GetMapping(value = "/api/food/{id}", produces = "application/json")
    public FoodDetailDTO getFood(@PathVariable String id) throws Exception {
        return fatSecretService.getFoodDetail(id);
    }

    private String extract(String xml, String key) {
        int start = xml.indexOf(key);
        if (start == -1) return "N/A";
        int end = xml.indexOf("|", start);
        if (end == -1) return "N/A";
        return xml.substring(start, end).replace(key, "").trim();
    }
    
}