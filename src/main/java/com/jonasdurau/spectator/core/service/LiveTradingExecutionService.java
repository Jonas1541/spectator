package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import com.jonasdurau.spectator.integration.binance.BinanceOrderService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementação de OrderExecutionService que executa ordens REAIS na Binance Futures.
 * 1. Envia ordem MARKET via BinanceOrderService
 * 2. Usa o avgPrice retornado pela Binance como preço de execução real
 * 3. Registra a posição no PositionManagerService para tracking interno
 * Ativado apenas quando spectator.trading.live-enabled=true.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class LiveTradingExecutionService implements OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(LiveTradingExecutionService.class);

    private final BinanceOrderService binanceOrderService;
    private final PositionManagerService positionManagerService;
    private final TradingMetricsService metricsService;
    private final NotificationService notificationService;
    private final BinanceRiskSyncService binanceRiskSyncService;
    private final PositionRepository positionRepository;

    public LiveTradingExecutionService(BinanceOrderService binanceOrderService,
                                        PositionManagerService positionManagerService,
                                        TradingMetricsService metricsService,
                                        NotificationService notificationService,
                                        BinanceRiskSyncService binanceRiskSyncService,
                                        PositionRepository positionRepository) {
        this.binanceOrderService = binanceOrderService;
        this.positionManagerService = positionManagerService;
        this.metricsService = metricsService;
        this.notificationService = notificationService;
        this.binanceRiskSyncService = binanceRiskSyncService;
        this.positionRepository = positionRepository;
        log.warn("🔥🔥🔥 LIVE TRADING EXECUTION SERVICE ACTIVE! Real orders will be sent to Binance. 🔥🔥🔥");
    }

    @Override
    public void executeMarketOrder(String strategyName, String symbol, TradeSide side, double quantity,
                                    double currentPrice, Double stopLoss, Double takeProfit,
                                    Double breakevenMultiplier, Double trailingMultiplier) {

        log.info("[LIVE TRADING] Sending {} {} MARKET order for {} (Qty: {})...", strategyName, side, symbol, quantity);

        try {
            // 1. Envia a ordem real para a Binance
            BinanceOrderResponse response = binanceOrderService.placeMarketOrder(symbol, side, quantity);

            // 2. Usa o preço médio de execução real retornado pela Binance
            double executionPrice = currentPrice;
            if (response.avgPrice() != null && !response.avgPrice().isEmpty() && !"0".equals(response.avgPrice())) {
                executionPrice = Double.parseDouble(response.avgPrice());
            }

            double executedQuantity = quantity;
            if (response.executedQty() != null && !response.executedQty().isEmpty()) {
                executedQuantity = Double.parseDouble(response.executedQty());
            }

            log.info("[LIVE TRADING] ✅ Order FILLED! orderId={}, avg price={}, executed qty={}, status={}",
                    response.orderId(), executionPrice, executedQuantity, response.status());

            metricsService.recordLiveOrderSuccess();

            // 3. Registra a posição internamente para tracking de SL/TP/trailing
            Position position = positionManagerService.openPosition(
                    strategyName, symbol, side, executionPrice, executedQuantity,
                    stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier);

            // 4. Coloca ordens de risco (SL/TP) na Binance para blindagem server-side
            if (stopLoss != null) {
                binanceRiskSyncService.placeStopLoss(position, stopLoss);
            }
            if (takeProfit != null) {
                binanceRiskSyncService.placeTakeProfit(position, takeProfit);
            }
            positionRepository.save(position);
        } catch (Exception e) {
            log.error("🚨 [LIVE TRADING] FAILED to execute {} {} order for {} (Qty: {}): {}",
                    strategyName, side, symbol, quantity, e.getMessage(), e);
            metricsService.recordLiveOrderFailed();
            notificationService.notifyCriticalError("LiveTrading",
                    String.format("Failed %s %s order for %s (Qty: %.6f): %s", strategyName, side, symbol, quantity, e.getMessage()));
            // Propaga o erro para que camadas superiores saibam que a ordem falhou
            throw new RuntimeException("Failed to execute live order on Binance: " + e.getMessage(), e);
        }
    }
}
