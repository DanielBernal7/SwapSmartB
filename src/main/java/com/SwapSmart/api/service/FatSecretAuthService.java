package com.SwapSmart.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;

@Service
public class FatSecretAuthService {

    @Value("${fatsecret.client-id}")
    private String clientId;

    @Value("${fatsecret.client-secret}")
    private String clientSecret;

    private String accessToken;
    private Instant expiryTime;

    private final WebClient webClient = WebClient.builder().build();

    public String getAccessToken() {

        // If token exists and hasn't expired, reuse it
        if (accessToken != null && expiryTime != null && Instant.now().isBefore(expiryTime)) {
            return accessToken;
        }
        Map<String, Object> response = webClient.post()
                .uri("https://oauth.fatsecret.com/connect/token")
                .headers(headers -> {
                    headers.setBasicAuth(clientId, clientSecret);
                    headers.set("Content-Type", "application/x-www-form-urlencoded");
                })
                .bodyValue("grant_type=client_credentials&scope=basic")
                .retrieve()
                .bodyToMono(Map.class)
                .block();
                
        accessToken = (String) response.get("access_token");
        Integer expiresIn = (Integer) response.get("expires_in");

        expiryTime = Instant.now().plusSeconds(expiresIn - 60);

        return accessToken;
    }
}