package com.jonasdurau.spectator.integration.binance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class BinanceConfig {

    private static final String BINANCE_FUTURES_BASE_URL = "https://fapi.binance.com";
    private static final String BINANCE_FUTURES_TESTNET_URL = "https://testnet.binancefuture.com";

    @Bean
    public RestClient binanceApi(@Value("${spectator.binance.use-testnet:false}") boolean useTestnet) {
        String baseUrl = useTestnet ? BINANCE_FUTURES_TESTNET_URL : BINANCE_FUTURES_BASE_URL;
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * RestClient autenticado com header X-MBX-APIKEY para endpoints privados da Binance.
     * Só é criado quando live trading está habilitado.
     */
    @Bean
    @ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
    public RestClient binanceAuthenticatedApi(
            @Value("${spectator.binance.api-key}") String apiKey,
            @Value("${spectator.binance.use-testnet:false}") boolean useTestnet) {
        String baseUrl = useTestnet ? BINANCE_FUTURES_TESTNET_URL : BINANCE_FUTURES_BASE_URL;
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-MBX-APIKEY", apiKey)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}