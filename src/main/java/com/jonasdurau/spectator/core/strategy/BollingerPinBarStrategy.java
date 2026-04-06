package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
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
public class BollingerPinBarStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(BollingerPinBarStrategy.class);

    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 2.0;

    public BollingerPinBarStrategy() {}

    @Override
    public String getName() {
        return "1H BB Pin Bar Mean Reversion";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext) {
        if (current4hRegime != MarketRegime.SIDEWAYS) {
            return TradeSignal.ignore();
        }

        // Necessita de 50 candles para a Média do ATR
        if (recent1hCandles.size() <= Math.max(BB_PERIOD, 50)) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Crypto_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        
        SMAIndicator sma20 = new SMAIndicator(closePrice, BB_PERIOD);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, BB_PERIOD);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(new BollingerBandsMiddleIndicator(sma20), stdDev, series.numFactory().numOf(BB_MULTIPLIER));
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(new BollingerBandsMiddleIndicator(sma20), stdDev, series.numFactory().numOf(BB_MULTIPLIER));
        
        ATRIndicator atr = new ATRIndicator(series, 14);
        SMAIndicator atrSma = new SMAIndicator(atr, 50);

        // DICA 2.4: Filtro de Volatilidade Morta. Se o ATR atual estiver abaixo da média histórica, abortar.
        if (atr.getValue(endIndex).doubleValue() < atrSma.getValue(endIndex).doubleValue()) {
            return TradeSignal.ignore();
        }

        double currentAtr = atr.getValue(endIndex).doubleValue();
        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double currentLow = lowPrice.getValue(endIndex).doubleValue();
        double currentHigh = highPrice.getValue(endIndex).doubleValue();

        double currentBbLower = bbLower.getValue(endIndex).doubleValue();
        double currentBbUpper = bbUpper.getValue(endIndex).doubleValue();
        double currentMiddle = sma20.getValue(endIndex).doubleValue();

        double candleSize = currentHigh - currentLow;
        if (candleSize == 0) return TradeSignal.ignore();

        double lowerWick = Math.min(cPrice, oPrice) - currentLow;
        double upperWick = currentHigh - Math.max(cPrice, oPrice);

        boolean isBullishPinBar = (lowerWick / candleSize) > 0.6 && cPrice >= oPrice;
        boolean isBearishPinBar = (upperWick / candleSize) > 0.6 && cPrice <= oPrice;

        // === LONG: Mean Reversion ===
        if (currentLow < currentBbLower && isBullishPinBar) {
            double stopLoss = currentLow - (currentAtr * 0.75); 
            double risk = cPrice - stopLoss;
            double reward = currentMiddle - cPrice; 

            if (risk > 0 && reward >= (risk * 1.0)) { 
                log.info("[{}] MEAN REVERSION LONG! SL: {}, Target: {}", getName(), stopLoss, currentMiddle);
                return TradeSignal.enter(TradeSide.LONG, stopLoss, currentMiddle, 1.0, null, null); 
            }
        }

        // === SHORT: Mean Reversion ===
        if (currentHigh > currentBbUpper && isBearishPinBar) {
            double stopLoss = currentHigh + (currentAtr * 0.75); 
            double risk = stopLoss - cPrice;
            double reward = cPrice - currentMiddle;

            if (risk > 0 && reward >= (risk * 1.0)) {
                log.info("[{}] MEAN REVERSION SHORT! SL: {}, Target: {}", getName(), stopLoss, currentMiddle);
                return TradeSignal.enter(TradeSide.SHORT, stopLoss, currentMiddle, 1.0, null, null); 
            }
        }

        return TradeSignal.ignore();
    }
}