package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.service.RiskManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.util.List;

@Component
public class MeanReversionStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(MeanReversionStrategy.class);

    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERSOLD = 30.0;
    
    private static final int BB_PERIOD = 20;
    private static final double BB_MULTIPLIER = 2.0;
    
    // ATR para calcular um Stop Loss fixo de segurança
    private static final int ATR_PERIOD = 14;
    private static final double ATR_SL_MULTIPLIER = 1.5;

    private final RiskManagerService riskManagerService;

    public MeanReversionStrategy(RiskManagerService riskManagerService) {
        this.riskManagerService = riskManagerService;
    }

    @Override
    public String getName() {
        return "1H BB Mean Reversion";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice) {
        if (current4hRegime != MarketRegime.SIDEWAYS) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= BB_PERIOD) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Bitcoin_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        
        // 1. RSI
        RSIIndicator rsi = new RSIIndicator(closePrice, RSI_PERIOD);
        double currentRsi = rsi.getValue(endIndex).doubleValue();

        // 2. Bollinger Bands
        SMAIndicator sma20 = new SMAIndicator(closePrice, BB_PERIOD);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, BB_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, series.numFactory().numOf(BB_MULTIPLIER));
        
        double currentBbLower = bbLower.getValue(endIndex).doubleValue();
        double currentBbMiddle = bbMiddle.getValue(endIndex).doubleValue();

        // 3. ATR para Stop Loss dinâmico (Fallback de proteção, caso o mercado despenque)
        ATRIndicator atr = new ATRIndicator(series, ATR_PERIOD);
        double currentAtr = atr.getValue(endIndex).doubleValue();
        
        double cPrice = closePrice.getValue(endIndex).doubleValue();

        // Regra de Compra: RSI Oversold (< 30) AND Preço tocando/abaixo da BB Lower
        if (currentRsi < RSI_OVERSOLD && cPrice <= currentBbLower) {
            log.info("[{}] Trigger detected! RSI ({}) < 30 and Price ({}) <= BB Lower ({}).", 
                     getName(), currentRsi, cPrice, currentBbLower);
            
            // O alvo em Mean Reversion é o retorno à média (BB Middle)
            double target = currentBbMiddle;
            
            // Stop Loss colocado abaixo da entrada baseado na volatilidade (ATR)
            double stopLoss = cPrice - (currentAtr * ATR_SL_MULTIPLIER);
            
            // Rejeitamos trades de Mean Reversion muito assimétricos negativamente
            if (target <= stopLoss) {
                log.warn("[{}] Discarding - Reward is smaller than Risk.", getName());
                return TradeSignal.ignore();
            }

            double quantity = riskManagerService.calculatePositionSize(cPrice, stopLoss);
            return TradeSignal.enter(TradeSide.LONG, quantity, stopLoss, target);
        }

        return TradeSignal.ignore();
    }
}
