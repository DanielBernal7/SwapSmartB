package com.SwapSmart.api.service;

import com.SwapSmart.api.dto.FatSecretFoodResponse;
import com.SwapSmart.api.dto.FoodDetailDTO;
import com.SwapSmart.api.dto.FatSecretSearchResponse;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class FatSecretService {

    private final FatSecretAuthService authService;
    private final WebClient webClient;
    private final XmlMapper xmlMapper;

    public FatSecretService(FatSecretAuthService authService) {
        this.authService = authService;
        this.webClient = WebClient.builder().build();
        this.xmlMapper = new XmlMapper();
    }

    public FatSecretSearchResponse searchFoods(String query) throws Exception {

        String token = authService.getAccessToken();

        String xmlResponse = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("platform.fatsecret.com")
                        .path("/rest/foods/search/v1")
                        .queryParam("search_expression", query)
                        .queryParam("page_number", 0)
                        .queryParam("max_results", 10)
                        .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return xmlMapper.readValue(xmlResponse, FatSecretSearchResponse.class);
    }

    public FoodDetailDTO getFoodDetail(String foodId) throws Exception {

        String token = authService.getAccessToken();

        String xmlResponse = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("platform.fatsecret.com")
                        .path("/rest/food/v2")
                        .queryParam("food_id", foodId)
                        .build())
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        XmlMapper xmlMapper = new XmlMapper();

        FatSecretFoodResponse response = xmlMapper.readValue(xmlResponse, FatSecretFoodResponse.class);

        FatSecretFoodResponse.Serving serving = response.servings.serving;

        return new FoodDetailDTO(
                response.foodId,
                response.foodName,
                response.brandName,
                serving.calories,
                serving.sugar,
                serving.fat,
                serving.carbs,
                serving.protein
        );
    }
}