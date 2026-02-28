package com.SwapSmart.api.dto;

public class SearchResultDTO {

    public String id;
    public String name;
    public String brand;
    public String description;

    public SearchResultDTO(String id, String name, String brand, String description) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.description = description;
    }
}