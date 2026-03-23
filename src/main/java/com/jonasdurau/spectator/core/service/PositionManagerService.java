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

    private final PositionRepository positionRepository;
    private final TradeRepository tradeRepository;

    public PositionManagerService(PositionRepository positionRepository, TradeRepository tradeRepository) {
        this.positionRepository = positionRepository;
        this.tradeRepository = tradeRepository;
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
    public void evaluateLiveTick(String symbol, double currentPrice, MarketRegime currentRegime) {
        List<Position> openPositions = positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);

        for (Position position : openPositions) {
            double pnl = position.calculateFloatingPnl(currentPrice);

            // ---> NOVO: GESTÃO DE TRADE: Breakeven e Trailing Paramétricos <---
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
                                currentStop = entryPrice; // Update memory for trailing step
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

                // --- 2. TRAILING LOGIC ---
                if (trailingMult != null) {
                    double trailingDistance = riskDistance * trailingMult;

                    if (position.getSide() == TradeSide.LONG) {
                        double potentialStop = currentPrice - trailingDistance;
                        if (potentialStop > currentStop) {
                            position.setStopLoss(potentialStop);
                            currentStop = potentialStop; 
                            stopMoved = true;
                            log.info("Trailing Stop moved HIGHER for LONG on {}! New Stop: {}", symbol, potentialStop);
                        }
                    } else { // SHORT
                        double potentialStop = currentPrice + trailingDistance;
                        if (potentialStop < currentStop) {
                            position.setStopLoss(potentialStop);
                            currentStop = potentialStop;
                            stopMoved = true;
                            log.info("Trailing Stop moved LOWER for SHORT on {}! New Stop: {}", symbol, potentialStop);
                        }
                    }
                }
            }
            
            if (stopMoved) {
                positionRepository.save(position); // Salva o novo stop no banco
            }
            // ---------------------------------------------------------------

            // --- PHASE 16: PARTIAL TAKE PROFIT (TP1) ---
            if (!position.isTp1Triggered() && position.getTp1Price() != null && position.getTp1Quantity() != null) {
                boolean tp1Hit = false;
                if (position.getSide() == TradeSide.LONG && currentPrice >= position.getTp1Price()) {
                    tp1Hit = true;
                } else if (position.getSide() == TradeSide.SHORT && currentPrice <= position.getTp1Price()) {
                    tp1Hit = true;
                }
                
                if (tp1Hit) {
                    double partialQty = position.getTp1Quantity();
                    double remainingQty = position.getQuantity() - partialQty;
                    
                    log.info("💰 TP1 HIT! Partially closing {} of {} at {}. Remaining: {}", partialQty, position.getSymbol(), currentPrice, remainingQty);
                    
                    // Record the partial exit trade
                    TradeSide exitSide = position.getSide() == TradeSide.LONG ? TradeSide.SHORT : TradeSide.LONG;
                    Trade partialTrade = new Trade(position, position.getSymbol(), exitSide, currentPrice, partialQty, Instant.now());
                    position.addTrade(partialTrade);
                    tradeRepository.save(partialTrade);
                    
                    position.setQuantity(remainingQty);
                    position.setTp1Triggered(true);
                    // Move stop to breakeven after partial
                    position.setStopLoss(position.getEntryPrice());
                    positionRepository.save(position);
                    log.info("✅ Stop moved to Breakeven after TP1. New quantity: {}", remainingQty);
                }
            }

            // --- PHASE 16: PANIC CLOSE (HMM Regime Shift) ---
            if (position.getStrategyName() != null && position.getStrategyName().toLowerCase().contains("pullback")) {
                boolean panicClose = false;
                if (position.getSide() == TradeSide.LONG && currentRegime != MarketRegime.TRENDING_UP) {
                    panicClose = true;
                } else if (position.getSide() == TradeSide.SHORT && currentRegime != MarketRegime.TRENDING_DOWN) {
                    panicClose = true;
                }
                
                if (panicClose) {
                    log.warn("🚨 PANIC CLOSE! Regime shifted to {} while holding {} {}. Closing at {}.", 
                             currentRegime, position.getSide(), position.getSymbol(), currentPrice);
                    closePosition(position, currentPrice);
                    continue;
                }
            }

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
