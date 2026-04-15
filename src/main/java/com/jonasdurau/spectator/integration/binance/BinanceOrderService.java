package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.integration.binance.dto.BinanceAlgoOrderResponse;
import com.jonasdurau.spectator.integration.binance.dto.BinanceOrderResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Serviço responsável por enviar ordens para a Binance Futures.
 * Ordens MARKET via POST /fapi/v1/order.
 * Ordens condicionais (STOP_MARKET, TAKE_PROFIT_MARKET) via POST /fapi/v1/algoOrder (Algo Order API).
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

        String plainQuantity = BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString();

        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&side=" + binanceSide
                + "&type=MARKET"
                + "&quantity=" + plainQuantity
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Supplier<BinanceOrderResponse> orderCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> executeOrderRequest(symbol, binanceSide, plainQuantity, timestamp, signature)
                )
        );

        BinanceOrderResponse response = orderCall.get();

        log.info("🔥 [LIVE ORDER] {} {} {} @ avg price: {} | orderId: {} | status: {}",
                binanceSide, quantity, symbol,
                response.avgPrice(), response.orderId(), response.status());

        return response;
    }

    private BinanceOrderResponse executeOrderRequest(String symbol, String side, String quantity,
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
     * Envia uma ordem STOP_MARKET via Algo Order API com closePosition=true.
     * O side é invertido: posição LONG → SELL, posição SHORT → BUY.
     * Desde Dez/2025, ordens condicionais devem usar POST /fapi/v1/algoOrder.
     * @param symbol        Ex: "BTCUSDT"
     * @param positionSide  O lado da posição aberta (LONG ou SHORT)
     * @param triggerPrice  Preço de ativação do stop
     * @return Resposta da Binance com algoId
     */
    public BinanceAlgoOrderResponse placeAlgoStopMarketOrder(String symbol, TradeSide positionSide, double triggerPrice) {
        String binanceSide = positionSide == TradeSide.LONG ? "SELL" : "BUY";
        String plainTriggerPrice = BigDecimal.valueOf(triggerPrice).stripTrailingZeros().toPlainString();
        
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&side=" + binanceSide
                + "&algoType=CONDITIONAL"
                + "&type=STOP_MARKET"
                + "&closePosition=true"
                + "&triggerPrice=" + plainTriggerPrice
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Supplier<BinanceAlgoOrderResponse> orderCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> authenticatedApi.post()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/fapi/v1/algoOrder")
                                        .queryParam("symbol", symbol)
                                        .queryParam("side", binanceSide)
                                        .queryParam("algoType", "CONDITIONAL")
                                        .queryParam("type", "STOP_MARKET")
                                        .queryParam("closePosition", "true")
                                        .queryParam("triggerPrice", plainTriggerPrice)
                                        .queryParam("timestamp", timestamp)
                                        .queryParam("signature", signature)
                                        .build())
                                .retrieve()
                                .body(BinanceAlgoOrderResponse.class)
                )
        );

        BinanceAlgoOrderResponse response = orderCall.get();
        log.info("🛡️ [ALGO STOP_MARKET] {} {} @ triggerPrice={} | algoId={} | status={}",
                binanceSide, symbol, plainTriggerPrice, response.algoId(), response.algoStatus());
        return response;
    }

    /**
     * Envia uma ordem TAKE_PROFIT_MARKET via Algo Order API com closePosition=true.
     * O side é invertido: posição LONG → SELL, posição SHORT → BUY.
     * Desde Dez/2025, ordens condicionais devem usar POST /fapi/v1/algoOrder.
     * @param symbol        Ex: "BTCUSDT"
     * @param positionSide  O lado da posição aberta (LONG ou SHORT)
     * @param triggerPrice  Preço de ativação do take profit
     * @return Resposta da Binance com algoId
     */
    public BinanceAlgoOrderResponse placeAlgoTakeProfitMarketOrder(String symbol, TradeSide positionSide, double triggerPrice) {
        String binanceSide = positionSide == TradeSide.LONG ? "SELL" : "BUY";
        String plainTriggerPrice = BigDecimal.valueOf(triggerPrice).stripTrailingZeros().toPlainString();
        
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol
                + "&side=" + binanceSide
                + "&algoType=CONDITIONAL"
                + "&type=TAKE_PROFIT_MARKET"
                + "&closePosition=true"
                + "&triggerPrice=" + plainTriggerPrice
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Supplier<BinanceAlgoOrderResponse> orderCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                RateLimiter.decorateSupplier(
                        rateLimiter,
                        () -> authenticatedApi.post()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/fapi/v1/algoOrder")
                                        .queryParam("symbol", symbol)
                                        .queryParam("side", binanceSide)
                                        .queryParam("algoType", "CONDITIONAL")
                                        .queryParam("type", "TAKE_PROFIT_MARKET")
                                        .queryParam("closePosition", "true")
                                        .queryParam("triggerPrice", plainTriggerPrice)
                                        .queryParam("timestamp", timestamp)
                                        .queryParam("signature", signature)
                                        .build())
                                .retrieve()
                                .body(BinanceAlgoOrderResponse.class)
                )
        );

        BinanceAlgoOrderResponse response = orderCall.get();
        log.info("🎯 [ALGO TAKE_PROFIT_MARKET] {} {} @ triggerPrice={} | algoId={} | status={}",
                binanceSide, symbol, plainTriggerPrice, response.algoId(), response.algoStatus());
        return response;
    }

    /**
     * Cancela uma algo order existente na Binance Futures via DELETE /fapi/v1/algoOrder.
     * @param algoId ID da algo order retornado pela Binance
     */
    public void cancelAlgoOrder(Long algoId) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "algoId=" + algoId
                + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        Runnable cancelCall = CircuitBreaker.decorateRunnable(
                circuitBreaker,
                RateLimiter.decorateRunnable(
                        rateLimiter,
                        () -> authenticatedApi.delete()
                                .uri(uriBuilder -> uriBuilder
                                        .path("/fapi/v1/algoOrder")
                                        .queryParam("algoId", algoId)
                                        .queryParam("timestamp", timestamp)
                                        .queryParam("signature", signature)
                                        .build())
                                .retrieve()
                                .toBodilessEntity()
                )
        );

        cancelCall.run();
        log.info("❌ [CANCEL ALGO ORDER] algoId={}", algoId);
    }

    /**
     * Cancela uma ordem regular existente na Binance Futures via DELETE /fapi/v1/order.
     * Mantido para ordens do tipo MARKET e outras ordens regulares.
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
