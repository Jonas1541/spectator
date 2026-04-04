package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.Candle;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Decorator que envolve o BinanceRestClient com Circuit Breaker e Rate Limiter.
 * Garante que chamadas REST respeitam os limites da Binance e degradam graciosamente.
 * Marcado como @Primary para que toda injeção de BinanceRestClient use esta versão resiliente.
 */
@Component
@Primary
public class ResilientBinanceRestClient extends BinanceRestClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientBinanceRestClient.class);

    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    public ResilientBinanceRestClient(RestClient restClient, CircuitBreaker circuitBreaker, RateLimiter rateLimiter) {
        super(restClient);
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<Candle> fetchHistoricalCandles(String symbol, String interval, int limit) {
        Supplier<List<Candle>> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> super.fetchHistoricalCandles(symbol, interval, limit)
                )
        );

        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("⚡ Circuit Breaker OPEN. Skipping REST call for {} ({}). Will retry after cooldown.", symbol, interval);
            return List.of();
        } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
            log.warn("🚦 Rate Limiter triggered. Too many requests to Binance. Skipping {} ({}).", symbol, interval);
            return List.of();
        }
    }

    @Override
    public List<Candle> fetchHistoricalCandles(String symbol, String interval, int limit, Instant startTime) {
        Supplier<List<Candle>> decorated = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> super.fetchHistoricalCandles(symbol, interval, limit, startTime)
                )
        );

        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            log.warn("⚡ Circuit Breaker OPEN. Skipping gap-fill REST call for {} ({}) from {}.", symbol, interval, startTime);
            return List.of();
        } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
            log.warn("🚦 Rate Limiter triggered. Skipping gap-fill for {} ({}).", symbol, interval);
            return List.of();
        }
    }
}
