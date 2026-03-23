package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import java.util.List;

public interface TradingStrategy {
    String getName();
    TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext);
}
