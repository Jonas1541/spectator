package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.TradeSide;

public record TradeSignal(
        boolean fire,
        TradeSide side,
        double quantity,
        Double stopLoss,
        Double takeProfit
) {
    public static TradeSignal ignore() {
        return new TradeSignal(false, null, 0, null, null);
    }

    public static TradeSignal enter(TradeSide side, double quantity, Double stopLoss, Double takeProfit) {
        return new TradeSignal(true, side, quantity, stopLoss, takeProfit);
    }
}
