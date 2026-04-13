package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import com.jonasdurau.spectator.integration.binance.BinanceExchangeInfoService;
import com.jonasdurau.spectator.integration.binance.BinanceOrderService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceOrderResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final BinanceExchangeInfoService exchangeInfoService;

    public LiveTradingExecutionService(BinanceOrderService binanceOrderService,
                                        PositionManagerService positionManagerService,
                                        TradingMetricsService metricsService,
                                        NotificationService notificationService,
                                        BinanceRiskSyncService binanceRiskSyncService,
                                        PositionRepository positionRepository,
                                        BinanceExchangeInfoService exchangeInfoService) {
        this.binanceOrderService = binanceOrderService;
        this.positionManagerService = positionManagerService;
        this.metricsService = metricsService;
        this.notificationService = notificationService;
        this.binanceRiskSyncService = binanceRiskSyncService;
        this.positionRepository = positionRepository;
        this.exchangeInfoService = exchangeInfoService;
        log.warn("🔥🔥🔥 LIVE TRADING EXECUTION SERVICE ACTIVE! Real orders will be sent to Binance. 🔥🔥🔥");
    }

    @Override
    public void executeMarketOrder(String strategyName, String symbol, TradeSide side, double quantity,
                                    double currentPrice, Double stopLoss, Double takeProfit,
                                    Double breakevenMultiplier, Double trailingMultiplier) {

        log.info("[LIVE TRADING] Sending {} {} MARKET order for {} (Qty: {})...", strategyName, side, symbol, quantity);

        // VARIÁVEIS DE CONTROLO PARA O FAILSAFE
        boolean orderPlacedOnExchange = false;
        Position openedPosition = null;
        double roundedQuantity = 0.0;

        try {
            // Obtém a precisão permitida para o símbolo e arredonda para baixo
            int precision = exchangeInfoService.getQuantityPrecision(symbol);
            roundedQuantity = BigDecimal.valueOf(quantity)
                    .setScale(precision, RoundingMode.DOWN)
                    .doubleValue();

            if (quantity != roundedQuantity) {
                log.info("[LIVE TRADING] Truncating qty from {} to {} for {}", quantity, roundedQuantity, symbol);
            }

            // 1. Envia a ordem real para a Binance
            BinanceOrderResponse response = binanceOrderService.placeMarketOrder(symbol, side, roundedQuantity);
            
            // 🔥 MARCO DE SEGURANÇA: Ordem foi executada na Binance com sucesso!
            orderPlacedOnExchange = true; 

            // 2. Usa o preço médio de execução real, se a Binance retornar um valor válido > 0
            double executionPrice = currentPrice;
            if (response.avgPrice() != null && !response.avgPrice().isEmpty()) {
                double parsedAvgPrice = Double.parseDouble(response.avgPrice());
                if (parsedAvgPrice > 0) {
                    executionPrice = parsedAvgPrice;
                }
            }

            // 3. Usa a quantidade executada da Binance, fazendo fallback para a arredondada
            double executedQuantity = roundedQuantity;
            if (response.executedQty() != null && !response.executedQty().isEmpty()) {
                double parsedQty = Double.parseDouble(response.executedQty());
                if (parsedQty > 0) {
                    executedQuantity = parsedQty;
                }
            }

            log.info("[LIVE TRADING] ✅ Order FILLED! orderId={}, avg price={}, executed qty={}, status={}",
                    response.orderId(), executionPrice, executedQuantity, response.status());

            metricsService.recordLiveOrderSuccess();

            // 4. Registra a posição internamente para tracking de SL/TP/trailing
            openedPosition = positionManagerService.openPosition(
                    strategyName, symbol, side, executionPrice, executedQuantity,
                    stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier);

            // 5. Coloca ordens de risco (SL/TP) na Binance para blindagem server-side
            if (stopLoss != null) {
                binanceRiskSyncService.placeStopLoss(openedPosition, stopLoss);
            }
            if (takeProfit != null) {
                binanceRiskSyncService.placeTakeProfit(openedPosition, takeProfit);
            }
            positionRepository.save(openedPosition);
            
        } catch (Exception e) {
            log.error("🚨 [LIVE TRADING] FAILED to execute {} {} order for {} (Qty: {}): {}",
                    strategyName, side, symbol, quantity, e.getMessage(), e);

            // ==========================================
            // 🛡️ PANIC FAILSAFE: PROTEÇÃO CONTRA ZUMBIS
            // ==========================================
            if (orderPlacedOnExchange) {
                log.warn("⚠️ [FAILSAFE] SL/TP failed for {}. Position is open on Binance without protection. Initiating emergency close!", symbol);
                try {
                    // Calcula a direção oposta para fechar a posição
                    TradeSide oppositeSide = (side == TradeSide.LONG) ? TradeSide.SHORT : TradeSide.LONG;
                    
                    // Envia ordem a mercado para fechar
                    binanceOrderService.placeMarketOrder(symbol, oppositeSide, roundedQuantity);
                    log.info("✅ [FAILSAFE] Emergency market close executed successfully for {}", symbol);

                    // Se a posição chegou a ser salva na base de dados, fechamos localmente também
                    if (openedPosition != null) {
                        positionManagerService.closePosition(openedPosition, currentPrice, "PANIC_FAILSAFE");
                    }
                } catch (Exception failsafeEx) {
                    // Pior cenário possível: a net caiu completamente e nem o failsafe passou.
                    log.error("🚨 [FATAL] Failsafe ALSO failed for {}! Manual intervention required immediately on Binance. Error: {}", symbol, failsafeEx.getMessage(), failsafeEx);
                    notificationService.notifyCriticalError("FATAL FAILSAFE ERROR", 
                            String.format("Failed to emergency close zombie %s position for %s. CHECK BINANCE ASAP!", side, symbol));
                }
            }

            metricsService.recordLiveOrderFailed();
            notificationService.notifyCriticalError("LiveTrading",
                    String.format("Failed %s %s order for %s (Qty: %.6f): %s", strategyName, side, symbol, quantity, e.getMessage()));
            
            // Propaga o erro
            throw new RuntimeException("Failed to execute live order on Binance: " + e.getMessage(), e);
        }
    }
}
