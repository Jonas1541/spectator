package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.Trade;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import com.jonasdurau.spectator.core.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class PositionManagerService {

    private static final Logger log = LoggerFactory.getLogger(PositionManagerService.class);

    /**
     * Threshold mínimo (0.2%) para atualizar o Trailing Stop na exchange.
     * Evita spam de requisições a cada micro-variação de preço.
     */
    private static final double TRAILING_STEP_THRESHOLD = 0.002;

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;
    private final TradingMetricsService metricsService;
    private final NotificationService notificationService;
    private final BinanceRiskSyncService binanceRiskSyncService;

    public PositionManagerService(PositionRepository positionRepository,
                                   TradeRepository tradeRepository,
                                   TradingMetricsService metricsService,
                                   NotificationService notificationService,
                                   BinanceRiskSyncService binanceRiskSyncService) {
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
        this.metricsService = metricsService;
        this.notificationService = notificationService;
        this.binanceRiskSyncService = binanceRiskSyncService;
    }

    @Transactional
    public Position openPosition(String strategyName, String symbol, TradeSide side, double entryPrice, double quantity, Double stopLoss,
            Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier) {
        log.info("Opening {} {} position for {} at {} (Qty: {})", strategyName, side, symbol, entryPrice, quantity);
        Position position = new Position(symbol, strategyName, side, entryPrice, quantity, stopLoss, takeProfit);

        Trade trade = new Trade(position, symbol, side, entryPrice, quantity, Instant.now());
        position.setBreakevenMultiplier(breakevenMultiplier);
        position.setTrailingMultiplier(trailingMultiplier);
        position.addTrade(trade);

        position = positionRepository.save(position);
        tradeRepository.save(trade);

        metricsService.recordPositionOpened();
        notificationService.notifyTradeEntry(symbol, strategyName, side.name(), entryPrice, quantity);

        return position;
    }

    /**
     * Fecha uma posição com motivo de saída explícito para métricas e notificações.
     * @param exitReason Ex: "STOP_LOSS", "TAKE_PROFIT"
     */
    @Transactional
    public void closePosition(Position position, double closingPrice, String exitReason) {
        log.info("Closing {} position for {} at {} (Reason: {})", position.getSide(), position.getSymbol(), closingPrice, exitReason);

        TradeSide exitSide = position.getSide() == TradeSide.LONG ? TradeSide.SHORT : TradeSide.LONG;
        Trade trade = new Trade(position, position.getSymbol(), exitSide, closingPrice, position.getQuantity(),
                Instant.now());

        position.addTrade(trade);

        // Cancela ordens residuais SL/TP na exchange antes de fechar localmente
        binanceRiskSyncService.cancelAllOrders(position);

        // Calcula o PnL realizado ANTES de mudar o status para CLOSED,
        // pois calculateFloatingPnl() retorna 0 se a posição já estiver fechada.
        position.setRealizedPnl(position.calculateFloatingPnl(closingPrice));
        position.closePosition(closingPrice);

        positionRepository.save(position);
        tradeRepository.save(trade);

        double pnl = position.getRealizedPnl();
        log.info("Position closed. Realized PnL: {}", pnl);

        // Métricas
        metricsService.recordPositionClosed(pnl);
        switch (exitReason) {
            case "STOP_LOSS" -> metricsService.recordStopLossHit();
            case "TAKE_PROFIT" -> metricsService.recordTakeProfitHit();
        }

        // Notificações
        notificationService.notifyTradeExit(
                position.getSymbol(), exitReason, position.getSide().name(), closingPrice, pnl);
    }

    @Transactional
    public void evaluateLiveTick(String symbol, double currentPrice, MarketRegime currentRegime) {
        List<Position> openPositions = positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);

        for (Position position : openPositions) {
            double pnl = position.calculateFloatingPnl(currentPrice);

            // ---> GESTÃO DE TRADE: Breakeven e Trailing Paramétricos <---
            boolean stopMoved = false;
            Double currentStop = position.getStopLoss();
            Double initialStop = position.getInitialStopLoss();
            Double breakevenMult = position.getBreakevenMultiplier();
            Double trailingMult = position.getTrailingMultiplier();
            double entryPrice = position.getEntryPrice();

            if (currentStop != null && initialStop != null) {
                double riskDistance = Math.abs(entryPrice - initialStop);

                // --- 1. BREAKEVEN LOGIC ---
                if (breakevenMult != null && Math.abs(currentStop - entryPrice) > 0.000001) {
                    if (position.getSide() == TradeSide.LONG) {
                        if (currentPrice >= (entryPrice + (riskDistance * breakevenMult))) {
                            if (currentStop < entryPrice) {
                                position.setStopLoss(entryPrice);
                                currentStop = entryPrice;
                                stopMoved = true;
                                log.info("Breakeven hit for LONG on {}! Stop moved to entry: {}", symbol, entryPrice);
                            }
                        }
                    } else { // SHORT
                        if (currentPrice <= (entryPrice - (riskDistance * breakevenMult))) {
                            if (currentStop > entryPrice) {
                                position.setStopLoss(entryPrice);
                                currentStop = entryPrice;
                                stopMoved = true;
                                log.info("Breakeven hit for SHORT on {}! Stop moved to entry: {}", symbol, entryPrice);
                            }
                        }
                    }
                }

                // --- 2. TRAILING LOGIC (com Threshold anti-spam) ---
                if (trailingMult != null) {
                    double trailingDistance = riskDistance * trailingMult;

                    if (position.getSide() == TradeSide.LONG) {
                        double potentialStop = currentPrice - trailingDistance;
                        if (potentialStop > currentStop) {
                            double stepPercent = Math.abs(potentialStop - currentStop) / Math.abs(currentStop);
                            if (stepPercent >= TRAILING_STEP_THRESHOLD) {
                                position.setStopLoss(potentialStop);
                                currentStop = potentialStop;
                                stopMoved = true;
                                log.info("✅ Trailing Step reached ({} >= {}%) for LONG on {}! New Stop: {}",
                                        String.format("%.4f", stepPercent * 100), TRAILING_STEP_THRESHOLD * 100, symbol, potentialStop);
                            }
                        }
                    } else { // SHORT
                        double potentialStop = currentPrice + trailingDistance;
                        if (potentialStop < currentStop) {
                            double stepPercent = Math.abs(currentStop - potentialStop) / Math.abs(currentStop);
                            if (stepPercent >= TRAILING_STEP_THRESHOLD) {
                                position.setStopLoss(potentialStop);
                                currentStop = potentialStop;
                                stopMoved = true;
                                log.info("✅ Trailing Step reached ({} >= {}%) for SHORT on {}! New Stop: {}",
                                        String.format("%.4f", stepPercent * 100), TRAILING_STEP_THRESHOLD * 100, symbol, potentialStop);
                            }
                        }
                    }
                }
            }

            if (stopMoved) {
                // Cancel & Replace: cancela SL antigo e coloca novo na exchange
                binanceRiskSyncService.cancelStopLoss(position);
                binanceRiskSyncService.placeStopLoss(position, currentStop);
                positionRepository.save(position);
            }

            // Checking Stop Loss
            if (position.getStopLoss() != null) {
                if (position.getSide() == TradeSide.LONG && currentPrice <= position.getStopLoss()) {
                    log.warn("Stop Loss hit for LONG position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice, "STOP_LOSS");
                    continue;
                } else if (position.getSide() == TradeSide.SHORT && currentPrice >= position.getStopLoss()) {
                    log.warn("Stop Loss hit for SHORT position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice, "STOP_LOSS");
                    continue;
                }
            }

            // Checking Take Profit
            if (position.getTakeProfit() != null) {
                if (position.getSide() == TradeSide.LONG && currentPrice >= position.getTakeProfit()) {
                    log.info("Take Profit hit for LONG position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice, "TAKE_PROFIT");
                } else if (position.getSide() == TradeSide.SHORT && currentPrice <= position.getTakeProfit()) {
                    log.info("Take Profit hit for SHORT position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice, "TAKE_PROFIT");
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Position> getOpenPositions(String symbol) {
        return positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);
    }
}
