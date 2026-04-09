package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.integration.binance.dto.BinanceOrderResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Serviço responsável por enviar ordens para a Binance Futures.
 * Envia ordens do tipo MARKET via POST /fapi/v1/order.
 * Protegido por Circuit Breaker e Rate Limiter.
 * Ativado apenas quando live trading está habilitado.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class BinanceOrderService {

    private static final Logger log = LoggerFactory.getLogger(BinanceOrderService.class);

    private final RestClient authenticatedApi;
    private final BinanceApiSigner signer;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;

    public BinanceOrderService(@Qualifier("binanceAuthenticatedApi") RestClient authenticatedApi,
                                BinanceApiSigner signer,
                                CircuitBreaker circuitBreaker,
                                RateLimiter rateLimiter) {
        this.authenticatedApi = authenticatedApi;
        this.signer = signer;
        this.circuitBreaker = circuitBreaker;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Envia uma ordem MARKET para a Binance Futures.
     * @param symbol  Ex: "BTCUSDT"
     * @param side    LONG → BUY, SHORT → SELL
     * @param quantity Quantidade do ativo
     * @return Resposta da Binance com orderId, avgPrice, executedQty etc.
     * @throws RuntimeException se a ordem falhar
     */
    public BinanceOrderResponse placeMarketOrder(String symbol, TradeSide side, double quantity) {
        // Binance Futures usa BUY/SELL (não LONG/SHORT)
        String binanceSide = side == TradeSide.LONG ? "BUY" : "SELL";

        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&side=" + binanceSide
                + "&type=MARKET"
                + "&quantity=" + quantity
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Supplier<BinanceOrderResponse> orderCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> executeOrderRequest(symbol, binanceSide, quantity, timestamp, signature)
                )
        );

        BinanceOrderResponse response = orderCall.get();

        log.info("🔥 [LIVE ORDER] {} {} {} @ avg price: {} | orderId: {} | status: {}",
                binanceSide, quantity, symbol,
                response.avgPrice(), response.orderId(), response.status());

        return response;
    }

    private BinanceOrderResponse executeOrderRequest(String symbol, String side, double quantity,
                                                      long timestamp, String signature) {
        return authenticatedApi.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/fapi/v1/order")
                        .queryParam("symbol", symbol)
                        .queryParam("side", side)
                        .queryParam("type", "MARKET")
                        .queryParam("quantity", quantity)
                        .queryParam("timestamp", timestamp)
                        .queryParam("signature", signature)
                        .build())
                .retrieve()
                .body(BinanceOrderResponse.class);
    }

    /**
     * Envia uma ordem STOP_MARKET com closePosition=true para a Binance Futures.
     * O side é invertido: posição LONG → SELL, posição SHORT → BUY.
     * @param symbol        Ex: "BTCUSDT"
     * @param positionSide  O lado da posição aberta (LONG ou SHORT)
     * @param stopPrice     Preço de ativação do stop
     * @return Resposta da Binance com orderId
     */
    public BinanceOrderResponse placeStopMarketOrder(String symbol, TradeSide positionSide, double stopPrice) {
        String binanceSide = positionSide == TradeSide.LONG ? "SELL" : "BUY";
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&side=" + binanceSide
                + "&type=STOP_MARKET"
                + "&closePosition=true"
                + "&stopPrice=" + stopPrice
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Supplier<BinanceOrderResponse> orderCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> authenticatedApi.post()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/fapi/v1/order")
                                        .queryParam("symbol", symbol)
                                        .queryParam("side", binanceSide)
                                        .queryParam("type", "STOP_MARKET")
                                        .queryParam("closePosition", "true")
                                        .queryParam("stopPrice", stopPrice)
                                        .queryParam("timestamp", timestamp)
                                        .queryParam("signature", signature)
                                        .build())
                                .retrieve()
                                .body(BinanceOrderResponse.class)
                )
        );

        BinanceOrderResponse response = orderCall.get();
        log.info("🛡️ [STOP_MARKET] {} {} @ stopPrice={} | orderId={} | status={}",
                binanceSide, symbol, stopPrice, response.orderId(), response.status());
        return response;
    }

    /**
     * Envia uma ordem TAKE_PROFIT_MARKET com closePosition=true para a Binance Futures.
     * O side é invertido: posição LONG → SELL, posição SHORT → BUY.
     * @param symbol        Ex: "BTCUSDT"
     * @param positionSide  O lado da posição aberta (LONG ou SHORT)
     * @param tpPrice       Preço de ativação do take profit
     * @return Resposta da Binance com orderId
     */
    public BinanceOrderResponse placeTakeProfitMarketOrder(String symbol, TradeSide positionSide, double tpPrice) {
        String binanceSide = positionSide == TradeSide.LONG ? "SELL" : "BUY";
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&side=" + binanceSide
                + "&type=TAKE_PROFIT_MARKET"
                + "&closePosition=true"
                + "&stopPrice=" + tpPrice
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Supplier<BinanceOrderResponse> orderCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> authenticatedApi.post()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/fapi/v1/order")
                                        .queryParam("symbol", symbol)
                                        .queryParam("side", binanceSide)
                                        .queryParam("type", "TAKE_PROFIT_MARKET")
                                        .queryParam("closePosition", "true")
                                        .queryParam("stopPrice", tpPrice)
                                        .queryParam("timestamp", timestamp)
                                        .queryParam("signature", signature)
                                        .build())
                                .retrieve()
                                .body(BinanceOrderResponse.class)
                )
        );

        BinanceOrderResponse response = orderCall.get();
        log.info("🎯 [TAKE_PROFIT_MARKET] {} {} @ tpPrice={} | orderId={} | status={}",
                binanceSide, symbol, tpPrice, response.orderId(), response.status());
        return response;
    }

    /**
     * Cancela uma ordem existente na Binance Futures via DELETE /fapi/v1/order.
     * @param symbol  Ex: "BTCUSDT"
     * @param orderId ID da ordem retornado pela Binance
     */
    public void cancelOrder(String symbol, Long orderId) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&orderId=" + orderId
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Runnable cancelCall = CircuitBreaker.decorateRunnable(
                circuitBreaker,
                RateLimiter.decorateRunnable(
                        rateLimiter,
                        () -> authenticatedApi.delete()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/fapi/v1/order")
                                        .queryParam("symbol", symbol)
                                        .queryParam("orderId", orderId)
                                        .queryParam("timestamp", timestamp)
                                        .queryParam("signature", signature)
                                        .build())
                                .retrieve()
                                .toBodilessEntity()
                )
        );

        cancelCall.run();
        log.info("❌ [CANCEL ORDER] symbol={} | orderId={}", symbol, orderId);
    }
}
