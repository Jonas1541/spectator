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
public class BollingerTrendPullbackStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(BollingerTrendPullbackStrategy.class);

    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 1.5;

    public BollingerTrendPullbackStrategy() {}

    @Override
    public String getName() {
        return "1H BB Trend Pullback";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext) {
        // Vamos operar retrações (pullbacks) extremas dentro de tendências estabelecidas!
        if (current4hRegime == MarketRegime.SIDEWAYS) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= BB_PERIOD) {
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

        double currentAtr = atr.getValue(endIndex).doubleValue();
        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double currentLow = lowPrice.getValue(endIndex).doubleValue();
        double currentHigh = highPrice.getValue(endIndex).doubleValue();

        double currentBbLower = bbLower.getValue(endIndex).doubleValue();
        double currentBbUpper = bbUpper.getValue(endIndex).doubleValue();

        double candleSize = currentHigh - currentLow;
        
        // Mantemos o filtro para evitar velas "mortas"
        if (candleSize == 0 || candleSize < (currentAtr * 0.3)) { 
            return TradeSignal.ignore();
        }

        double lowerWick = Math.min(cPrice, oPrice) - currentLow;
        double upperWick = currentHigh - Math.max(cPrice, oPrice);

        // MUDANÇA DE OURO: 
        // 1. Pavio de 1/3 (0.33) já configura rejeição.
        // 2. REMOVEMOS a obrigação de fechar verde (LONG) ou vermelho (SHORT). 
        // O que importa é a varredura da liquidez na banda (o tamanho do pavio).
        boolean isBullishRejection = (lowerWick / candleSize) >= 0.33;
        boolean isBearishRejection = (upperWick / candleSize) >= 0.33;

        // === LONG: Pullback numa Tendência de ALTA ===
        if (current4hRegime == MarketRegime.TRENDING_UP) {
            // Preço desabou até a banda inferior e deixou rejeição (pavio)
            if (currentLow < currentBbLower && isBullishRejection) {
                double stopLoss = currentLow - (currentAtr * 0.5); 
                double risk = cPrice - stopLoss;
                if (risk <= 0) return TradeSignal.ignore();

                double takeProfit = cPrice * 10.0; 
                double trailingMultiplier = (currentAtr * 2.0) / risk;

                log.info("[{}] LONG Trend Pullback! SL: {:.2f}, ATR: {:.2f}", getName(), stopLoss, currentAtr);
                return TradeSignal.enter(TradeSide.LONG, stopLoss, takeProfit, null, trailingMultiplier, null); 
            }
        }

        // === SHORT: Pullback numa Tendência de BAIXA ===
        if (current4hRegime == MarketRegime.TRENDING_DOWN) {
            // Preço espirrou até a banda superior e deixou rejeição (pavio)
            if (currentHigh > currentBbUpper && isBearishRejection) {
                double stopLoss = currentHigh + (currentAtr * 0.5); 
                double risk = stopLoss - cPrice;
                if (risk <= 0) return TradeSignal.ignore();

                double takeProfit = 0.0001; 
                double trailingMultiplier = (currentAtr * 2.0) / risk;

                log.info("[{}] SHORT Trend Pullback! SL: {:.2f}, ATR: {:.2f}", getName(), stopLoss, currentAtr);
                return TradeSignal.enter(TradeSide.SHORT, stopLoss, takeProfit, null, trailingMultiplier, null); 
            }
        }

        return TradeSignal.ignore();
    }
}