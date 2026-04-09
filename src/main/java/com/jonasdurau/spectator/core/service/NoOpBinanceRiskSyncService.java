package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementação no-op de BinanceRiskSyncService para Paper Trading e Backtest.
 * Não envia nenhuma ordem à exchange; o gerenciamento de risco é feito
 * inteiramente pelo PositionManagerService em memória/DB local.
 * Ativado quando spectator.trading.live-enabled=false (ou ausente).
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "false", matchIfMissing = true)
public class NoOpBinanceRiskSyncService implements BinanceRiskSyncService {

    private static final Logger log = LoggerFactory.getLogger(NoOpBinanceRiskSyncService.class);

    public NoOpBinanceRiskSyncService() {
        log.info("📄 NoOp Risk Sync active (Paper Trading / Backtest mode).");
    }

    @Override
    public Long placeStopLoss(Position position, double stopPrice) {
        return null;
    }

    @Override
    public Long placeTakeProfit(Position position, double tpPrice) {
        return null;
    }

    @Override
    public void cancelStopLoss(Position position) {
        // No-op
    }

    @Override
    public void cancelTakeProfit(Position position) {
        // No-op
    }

    @Override
    public void cancelAllOrders(Position position) {
        // No-op
    }
}
