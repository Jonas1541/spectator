package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.TradeSide;

public record TradeSignal(
        boolean fire,
        TradeSide side,
        Double stopLoss,
        Double takeProfit,
        Double breakevenMultiplier,
        Double trailingMultiplier,
        Double winProbability,
        Double tp1Price,
        Double tp1SizePct
) {
    public static TradeSignal ignore() {
        return new TradeSignal(false, null, null, null, null, null, null, null, null);
    }

    public static TradeSignal enter(TradeSide side, Double stopLoss, Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier, Double winProbability) {
        return new TradeSignal(true, side, stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier, winProbability, null, null);
    }

    public static TradeSignal enterWithPartialTp(TradeSide side, Double stopLoss, Double takeProfit, Double breakevenMultiplier, Double trailingMultiplier, Double winProbability, Double tp1Price, Double tp1SizePct) {
        return new TradeSignal(true, side, stopLoss, takeProfit, breakevenMultiplier, trailingMultiplier, winProbability, tp1Price, tp1SizePct);
    }
}
