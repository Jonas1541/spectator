package com.jonasdurau.spectator.integration.binance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class BinanceConfig {

    @Bean
    public RestClient binanceApi() {
        return RestClient.builder()
                .baseUrl("https://api.binance.com")
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}