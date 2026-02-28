package com.SwapSmart.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class FatSecretSearchResponse {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "food")
    public List<Food> food;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Food {

        @JacksonXmlProperty(localName = "food_id")
        public String foodId;

        @JacksonXmlProperty(localName = "food_name")
        public String foodName;

        @JacksonXmlProperty(localName = "brand_name")
        public String brandName;

        @JacksonXmlProperty(localName = "food_description")
        public String description;
    }
}