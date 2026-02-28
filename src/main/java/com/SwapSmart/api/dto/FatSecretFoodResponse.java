package com.SwapSmart.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FatSecretFoodResponse {

    @JacksonXmlProperty(localName = "food_id")
    public String foodId;

    @JacksonXmlProperty(localName = "food_name")
    public String foodName;

    @JacksonXmlProperty(localName = "brand_name")
    public String brandName;

    @JacksonXmlProperty(localName = "servings")
    public Servings servings;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Servings {

        @JacksonXmlProperty(localName = "serving")
        public Serving serving;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Serving {

        @JacksonXmlProperty(localName = "calories")
        public String calories;

        @JacksonXmlProperty(localName = "fat")
        public String fat;

        @JacksonXmlProperty(localName = "carbohydrate")
        public String carbs;

        @JacksonXmlProperty(localName = "protein")
        public String protein;

        @JacksonXmlProperty(localName = "sugar")
        public String sugar;
    }
}