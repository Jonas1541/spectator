package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;

import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;

import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.util.List;

@Component
public class MeanReversionStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(MeanReversionStrategy.class);

    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERSOLD = 25.0;
    private static final double RSI_OVERBOUGHT = 75.0;
    
    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 2.0;
    


    public MeanReversionStrategy() {}

    @Override
    public String getName() {
        return "1H BB Mean Reversion";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext) {
        if (current4hRegime != MarketRegime.SIDEWAYS) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= BB_PERIOD) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Bitcoin_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_PERIOD);
        double currentRsi = rsi.getValue(endIndex).doubleValue();

        SMAIndicator sma20 = new SMAIndicator(closePrice, BB_PERIOD);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, BB_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, series.numFactory().numOf(BB_MULTIPLIER));
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev, series.numFactory().numOf(BB_MULTIPLIER));

        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double currentLow = lowPrice.getValue(endIndex).doubleValue();
        double currentHigh = highPrice.getValue(endIndex).doubleValue();

        double currentBbLower = bbLower.getValue(endIndex).doubleValue();
        double currentBbUpper = bbUpper.getValue(endIndex).doubleValue();
        double currentBbMiddle = bbMiddle.getValue(endIndex).doubleValue();

        // === LONG: Pin Bar na Banda Inferior (Vela Única) ===
        // A mínima tocou/caiu ABAIXO da banda inferior, mas o close recuperou ACIMA dela, numa vela de alta
        boolean longWickSweep = currentLow < currentBbLower && cPrice > currentBbLower && cPrice > oPrice;

        if (longWickSweep && currentRsi < RSI_OVERSOLD) {
            // Stop Loss no fundo exato do Pin Bar (pavio)
            double stopLoss = lowPrice.getValue(endIndex).doubleValue();
            double risk = cPrice - stopLoss;
            // Take Profit projetado a 1.5x o risco
            double takeProfit = cPrice + (risk * 1.5);
            
            log.info("[{}] PIN BAR LONG! Low ({}) swept BB Lower ({}), close ({}) recovered above. RSI: {}. SL: {}, TP: {}", 
                     getName(), String.format("%.2f", currentLow), String.format("%.2f", currentBbLower), String.format("%.2f", cPrice), 
                     String.format("%.1f", currentRsi), String.format("%.2f", stopLoss), String.format("%.2f", takeProfit));

            return TradeSignal.enter(TradeSide.LONG, stopLoss, takeProfit, null, null, 0.60);
        }

        // === SHORT: Pin Bar na Banda Superior (Vela Única) ===
        // A máxima tocou/subiu ACIMA da banda superior, mas o close retraiu ABAIXO dela, numa vela de baixa
        boolean shortWickSweep = currentHigh > currentBbUpper && cPrice < currentBbUpper && cPrice < oPrice;

        if (shortWickSweep && currentRsi > RSI_OVERBOUGHT) {
            // Stop Loss no topo exato do Pin Bar (pavio)
            double stopLoss = highPrice.getValue(endIndex).doubleValue();
            double risk = stopLoss - cPrice;
            // Take Profit projetado a 1.5x o risco
            double takeProfit = cPrice - (risk * 1.5);
            
            log.info("[{}] PIN BAR SHORT! High ({}) swept BB Upper ({}), close ({}) recovered below. RSI: {}. SL: {}, TP: {}", 
                     getName(), String.format("%.2f", currentHigh), String.format("%.2f", currentBbUpper), String.format("%.2f", cPrice), 
                     String.format("%.1f", currentRsi), String.format("%.2f", stopLoss), String.format("%.2f", takeProfit));

            return TradeSignal.enter(TradeSide.SHORT, stopLoss, takeProfit, null, null, 0.60);
        }

        return TradeSignal.ignore();
    }
}
