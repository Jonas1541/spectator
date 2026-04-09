package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.service.BinanceRiskSyncService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementação real de BinanceRiskSyncService que envia ordens de risco
 * (STOP_MARKET / TAKE_PROFIT_MARKET) para a Binance Futures.
 * Ativado apenas quando spectator.trading.live-enabled=true.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class LiveBinanceRiskSyncService implements BinanceRiskSyncService {

    private static final Logger log = LoggerFactory.getLogger(LiveBinanceRiskSyncService.class);

    private final BinanceOrderService binanceOrderService;

    public LiveBinanceRiskSyncService(BinanceOrderService binanceOrderService) {
        this.binanceOrderService = binanceOrderService;
        log.warn("🔥 Live Binance Risk Sync active! SL/TP orders will be sent to Binance.");
    }

    @Override
    public Long placeStopLoss(Position position, double stopPrice) {
        try {
            BinanceOrderResponse response = binanceOrderService.placeStopMarketOrder(
                    position.getSymbol(), position.getSide(), stopPrice);
            Long orderId = response.orderId();
            position.setBinanceSlOrderId(orderId);
            return orderId;
        } catch (Exception e) {
            log.error("🚨 Failed to place STOP_MARKET for {} at {}: {}",
                    position.getSymbol(), stopPrice, e.getMessage());
            return null;
        }
    }

    @Override
    public Long placeTakeProfit(Position position, double tpPrice) {
        try {
            BinanceOrderResponse response = binanceOrderService.placeTakeProfitMarketOrder(
                    position.getSymbol(), position.getSide(), tpPrice);
            Long orderId = response.orderId();
            position.setBinanceTpOrderId(orderId);
            return orderId;
        } catch (Exception e) {
            log.error("🚨 Failed to place TAKE_PROFIT_MARKET for {} at {}: {}",
                    position.getSymbol(), tpPrice, e.getMessage());
            return null;
        }
    }

    @Override
    public void cancelStopLoss(Position position) {
        Long orderId = position.getBinanceSlOrderId();
        if (orderId != null) {
            try {
                binanceOrderService.cancelOrder(position.getSymbol(), orderId);
            } catch (Exception e) {
                log.warn("⚠️ Failed to cancel SL order {} for {}: {}",
                        orderId, position.getSymbol(), e.getMessage());
            }
            position.setBinanceSlOrderId(null);
        }
    }

    @Override
    public void cancelTakeProfit(Position position) {
        Long orderId = position.getBinanceTpOrderId();
        if (orderId != null) {
            try {
                binanceOrderService.cancelOrder(position.getSymbol(), orderId);
            } catch (Exception e) {
                log.warn("⚠️ Failed to cancel TP order {} for {}: {}",
                        orderId, position.getSymbol(), e.getMessage());
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
