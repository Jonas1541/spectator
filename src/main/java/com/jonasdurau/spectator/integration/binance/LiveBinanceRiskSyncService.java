package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.service.BinanceRiskSyncService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceAlgoOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementação real de BinanceRiskSyncService que envia ordens de risco
 * (STOP_MARKET / TAKE_PROFIT_MARKET) para a Binance Futures via Algo Order API.
 * Desde Dez/2025, ordens condicionais devem usar POST /fapi/v1/algoOrder.
 * Ativado apenas quando spectator.trading.live-enabled=true.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class LiveBinanceRiskSyncService implements BinanceRiskSyncService {

    private static final Logger log = LoggerFactory.getLogger(LiveBinanceRiskSyncService.class);

    private final BinanceOrderService binanceOrderService;

    public LiveBinanceRiskSyncService(BinanceOrderService binanceOrderService) {
        this.binanceOrderService = binanceOrderService;
        log.warn("🔥 Live Binance Risk Sync active! SL/TP orders will be sent to Binance via Algo Order API.");
    }

    @Override
    public Long placeStopLoss(Position position, double stopPrice) {
        try {
            BinanceAlgoOrderResponse response = binanceOrderService.placeAlgoStopMarketOrder(
                    position.getSymbol(), position.getSide(), stopPrice);
            Long algoId = response.algoId();
            position.setBinanceSlOrderId(algoId);
            return algoId;
        } catch (Exception e) {
            log.error("🚨 Failed to place ALGO STOP_MARKET for {} at {}: {}",
                    position.getSymbol(), stopPrice, e.getMessage());
            return null;
        }
    }

    @Override
    public Long placeTakeProfit(Position position, double tpPrice) {
        try {
            BinanceAlgoOrderResponse response = binanceOrderService.placeAlgoTakeProfitMarketOrder(
                    position.getSymbol(), position.getSide(), tpPrice);
            Long algoId = response.algoId();
            position.setBinanceTpOrderId(algoId);
            return algoId;
        } catch (Exception e) {
            log.error("🚨 Failed to place ALGO TAKE_PROFIT_MARKET for {} at {}: {}",
                    position.getSymbol(), tpPrice, e.getMessage());
            return null;
        }
    }

    @Override
    public void cancelStopLoss(Position position) {
        Long algoId = position.getBinanceSlOrderId();
        if (algoId != null) {
            try {
                binanceOrderService.cancelAlgoOrder(algoId);
            } catch (Exception e) {
                log.warn("⚠️ Failed to cancel SL algo order {} for {}: {}",
                        algoId, position.getSymbol(), e.getMessage());
            }
            position.setBinanceSlOrderId(null);
        }
    }

    @Override
    public void cancelTakeProfit(Position position) {
        Long algoId = position.getBinanceTpOrderId();
        if (algoId != null) {
            try {
                binanceOrderService.cancelAlgoOrder(algoId);
            } catch (Exception e) {
                log.warn("⚠️ Failed to cancel TP algo order {} for {}: {}",
                        algoId, position.getSymbol(), e.getMessage());
            }
            position.setBinanceTpOrderId(null);
        }
    }

    @Override
    public void cancelAllOrders(Position position) {
        cancelStopLoss(position);
        cancelTakeProfit(position);
    }
}
