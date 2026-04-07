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
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.util.List;

@Component
public class BollingerBreakoutStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(BollingerBreakoutStrategy.class);

    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 1.5;
    private static final double VOLUME_FACTOR = 1.3;      // volume deve ser > média * este fator
    private static final double STOP_ATR_MULTIPLIER = 0.75; // stop mais largo (antes 0.5)

    public BollingerBreakoutStrategy() {}

    @Override
    public String getName() {
        return "1H BB Volatility Breakout";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext) {
        if (recent1hCandles.size() <= BB_PERIOD) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Crypto_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSma = new SMAIndicator(volume, BB_PERIOD);

        SMAIndicator sma20 = new SMAIndicator(closePrice, BB_PERIOD);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, BB_PERIOD);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(
                new BollingerBandsMiddleIndicator(sma20), stdDev, series.numFactory().numOf(BB_MULTIPLIER));
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(
                new BollingerBandsMiddleIndicator(sma20), stdDev, series.numFactory().numOf(BB_MULTIPLIER));

        ATRIndicator atr = new ATRIndicator(series, 14);
        double currentAtr = atr.getValue(endIndex).doubleValue();

        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double currentLower = bbLower.getValue(endIndex).doubleValue();
        double currentUpper = bbUpper.getValue(endIndex).doubleValue();
        double currentMiddle = sma20.getValue(endIndex).doubleValue();

        double currentVol = volume.getValue(endIndex).doubleValue();
        double avgVol = volumeSma.getValue(endIndex).doubleValue();

        boolean isHighVolumeBreakout = currentVol > (avgVol * VOLUME_FACTOR);

        // === LONG BREAKOUT ===
        if (cPrice > currentUpper && isHighVolumeBreakout) {
            // SUCESSO DA EMA: Operar a favor da tendência forte apenas
            if (current4hRegime != MarketRegime.TRENDING_UP) {
                return TradeSignal.ignore();
            }

            double stopLoss = currentMiddle - (currentAtr * STOP_ATR_MULTIPLIER);
            double risk = cPrice - stopLoss;
            if (risk <= 0) return TradeSignal.ignore();

            // SUCESSO DA EMA: Trailing Stop ao invés de alvo fixo
            double takeProfit = cPrice * 10.0; // Alvo virtual alto
            double trailingMultiplier = (currentAtr * 2.0) / risk; // Mesmo trailing da EMA

            log.info("[{}] LONG BREAKOUT! Volume: {}x. SL: {:.2f}, ATR: {}",
                     getName(), currentVol / avgVol, stopLoss, String.format("%.2f", currentAtr));

            return TradeSignal.enter(TradeSide.LONG, stopLoss, takeProfit, null, trailingMultiplier, null);
        }

        // === SHORT BREAKOUT ===
        if (cPrice < currentLower && isHighVolumeBreakout) {
            if (current4hRegime != MarketRegime.TRENDING_DOWN) {
                return TradeSignal.ignore();
            }

            double stopLoss = currentMiddle + (currentAtr * STOP_ATR_MULTIPLIER);
            double risk = stopLoss - cPrice;
            if (risk <= 0) return TradeSignal.ignore();

            double takeProfit = 0.0001; // Alvo virtual próximo de 0
            double trailingMultiplier = (currentAtr * 2.0) / risk;

            log.info("[{}] SHORT BREAKOUT! Volume: {}x. SL: {:.2f}, ATR: {}",
                     getName(), currentVol / avgVol, stopLoss, String.format("%.2f", currentAtr));

            return TradeSignal.enter(TradeSide.SHORT, stopLoss, takeProfit, null, trailingMultiplier, null);
        }

        return TradeSignal.ignore();
    }
}