package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
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

    private static final double BREAKEVEN_ACTIVATION_PCT = 0.01;
    private static final double TRAILING_STOP_DISTANCE_PCT = 0.01;

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;

    public PositionManagerService(PositionRepository positionRepository, TradeRepository tradeRepository) {
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
    }

    @Transactional
    public Position openPosition(String symbol, TradeSide side, double entryPrice, double quantity, Double stopLoss,
            Double takeProfit) {
        log.info("Opening {} position for {} at {} (Qty: {})", side, symbol, entryPrice, quantity);
        Position position = new Position(symbol, side, entryPrice, quantity, stopLoss, takeProfit);

        Trade trade = new Trade(position, symbol, side, entryPrice, quantity, Instant.now());
        position.addTrade(trade);

        position = positionRepository.save(position);
        tradeRepository.save(trade);

        return position;
    }

    @Transactional
    public void closePosition(Position position, double closingPrice) {
        log.info("Closing {} position for {} at {}", position.getSide(), position.getSymbol(), closingPrice);

        TradeSide exitSide = position.getSide() == TradeSide.LONG ? TradeSide.SHORT : TradeSide.LONG;
        Trade trade = new Trade(position, position.getSymbol(), exitSide, closingPrice, position.getQuantity(),
                Instant.now());

        position.addTrade(trade);
        position.closePosition(closingPrice);

        positionRepository.save(position);
        tradeRepository.save(trade);
        log.info("Position closed. Realized PnL: {}", position.getRealizedPnl());
    }

    @Transactional
    public void evaluateLiveTick(String symbol, double currentPrice) {
        List<Position> openPositions = positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);

        for (Position position : openPositions) {
            double pnl = position.calculateFloatingPnl(currentPrice);

            // ---> GESTÃO ATIVA DO TRADE (Trailing Stop & Breakeven) <---
            boolean stopMoved = false;
            if (position.getSide() == TradeSide.LONG) {
                double profitPct = (currentPrice - position.getEntryPrice()) / position.getEntryPrice();
                if (profitPct >= BREAKEVEN_ACTIVATION_PCT) {
                    double newStop = Math.max(position.getStopLoss(), position.getEntryPrice()); // Garante o zero-a-zero
                    double trailingLevel = currentPrice * (1.0 - TRAILING_STOP_DISTANCE_PCT);
                    if (trailingLevel > newStop) newStop = trailingLevel; // Arrasta pra cima
                    
                    if (newStop > position.getStopLoss()) {
                        position.setStopLoss(newStop);
                        stopMoved = true;
                        log.info("Trailing Stop for LONG {} moved up to {}", symbol, newStop);
                    }
                }
            } else { // SHORT
                double profitPct = (position.getEntryPrice() - currentPrice) / position.getEntryPrice();
                if (profitPct >= BREAKEVEN_ACTIVATION_PCT) {
                    double newStop = Math.min(position.getStopLoss(), position.getEntryPrice()); // Garante o zero-a-zero
                    double trailingLevel = currentPrice * (1.0 + TRAILING_STOP_DISTANCE_PCT);
                    if (trailingLevel < newStop) newStop = trailingLevel; // Arrasta pra baixo
                    
                    if (newStop < position.getStopLoss()) {
                        position.setStopLoss(newStop);
                        stopMoved = true;
                        log.info("Trailing Stop for SHORT {} moved down to {}", symbol, newStop);
                    }
                }
            }
            if (stopMoved) {
                positionRepository.save(position); // Salva o novo stop no banco
            }
            // ---------------------------------------------------------------

            // Checking Stop Loss
            if (position.getStopLoss() != null) {
                if (position.getSide() == TradeSide.LONG && currentPrice <= position.getStopLoss()) {
                    log.warn("Stop Loss hit for LONG position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice);
                    continue;
                } else if (position.getSide() == TradeSide.SHORT && currentPrice >= position.getStopLoss()) {
                    log.warn("Stop Loss hit for SHORT position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice);
                    continue;
                }
            }

            // Checking Take Profit
            if (position.getTakeProfit() != null) {
                if (position.getSide() == TradeSide.LONG && currentPrice >= position.getTakeProfit()) {
                    log.info("Take Profit hit for LONG position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice);
                } else if (position.getSide() == TradeSide.SHORT && currentPrice <= position.getTakeProfit()) {
                    log.info("Take Profit hit for SHORT position on {}! Closing at {}. Floating PnL was: {}", symbol,
                            currentPrice, pnl);
                    closePosition(position, currentPrice);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Position> getOpenPositions(String symbol) {
        return positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);
    }
}
