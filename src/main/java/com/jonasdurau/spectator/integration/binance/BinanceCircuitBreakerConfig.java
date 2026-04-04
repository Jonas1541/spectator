package com.jonasdurau.spectator.integration.binance;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;

/**
 * Configuração programática do Resilience4j para proteger chamadas REST à Binance.
 * Usa API programática (sem auto-config) para compatibilidade com Spring Boot 4.x.
 */
@Configuration
public class BinanceCircuitBreakerConfig {

    /**
     * Circuit Breaker para chamadas REST à Binance.
     * - Abre se 50% das últimas 10 chamadas falharem
     * - Espera 30s no estado OPEN antes de testar novamente
     * - Testa com 3 chamadas no estado HALF_OPEN
     */
    @Bean
    public CircuitBreaker binanceCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(RestClientException.class, IOException.class)
                .build();
        return CircuitBreaker.of("binance", config);
    }

    /**
     * Rate Limiter para respeitar os limites de taxa da Binance.
     * - Binance Futures permite ~1200 requests/minuto para dados de mercado
     * - Espera até 5s se estiver no limite antes de rejeitar
     */
    @Bean
    public RateLimiter binanceRateLimiter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(1200)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
        return RateLimiter.of("binance", config);
    }
}
