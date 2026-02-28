package com.SwapSmart.api.dto;

public class FoodDetailDTO {

    public String id;
    public String name;
    public String brand;
    public String calories;
    public String sugar;
    public String fat;
    public String carbs;
    public String protein;

    public FoodDetailDTO(String id, String name, String brand,
                         String calories, String sugar,
                         String fat, String carbs,
                         String protein) {
        this.id = id;
        this.name = name;
        this.brand = brand;
        this.calories = calories;
        this.sugar = sugar;
        this.fat = fat;
        this.carbs = carbs;
        this.protein = protein;
    }
}