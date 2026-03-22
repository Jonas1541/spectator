package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.service.RiskManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.util.List;

@Component
public class PullbackTrendStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PullbackTrendStrategy.class);

    private static final int EMA_50 = 50;
    
    // Distância máxima permitida até a EMA 50 para considerar o "toque" (1%)
    private static final double MAX_PULLBACK_DISTANCE_PCT = 0.01; 

    private final RiskManagerService riskManagerService;

    public PullbackTrendStrategy(RiskManagerService riskManagerService) {
        this.riskManagerService = riskManagerService;
    }

    @Override
    public String getName() {
        return "1H 50-EMA Pullback Engine";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice) {
        if (current4hRegime != MarketRegime.TRENDING_UP && current4hRegime != MarketRegime.TRENDING_DOWN) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= EMA_50) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Bitcoin_1H");
        int endIndex = series.getEndIndex();

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        EMAIndicator ema50 = new EMAIndicator(closePrice, EMA_50);
        
        // NOVO: Filtro de Volume Institucional
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSma = new SMAIndicator(volume, 20); // Média de volume das últimas 20 horas
        
        // NOVO: ATR para o Stop Loss Dinâmico
        ATRIndicator atr = new ATRIndicator(series, 14);

        // NOVO: MACD para confirmar o Momento (Momentum)
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignalLine = new EMAIndicator(macd, 9);
        
        double currentMacd = macd.getValue(endIndex).doubleValue();
        double currentSignal = macdSignalLine.getValue(endIndex).doubleValue();
        
        // MACD Bullish = Linha MACD > Linha de Sinal
        boolean isMacdBullish = currentMacd > currentSignal;
        boolean isMacdBearish = currentMacd < currentSignal;
        
        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double e50 = ema50.getValue(endIndex).doubleValue();
        double currentVolume = volume.getValue(endIndex).doubleValue();
        double avgVolume = volumeSma.getValue(endIndex).doubleValue();
        double currentAtr = atr.getValue(endIndex).doubleValue();

        double distanceToEma = Math.abs((cPrice - e50) / e50);
        boolean nearEma = distanceToEma <= MAX_PULLBACK_DISTANCE_PCT;
        
        // Regra de Ouro: Só operamos se o volume atual for maior que a média!
        boolean strongVolume = currentVolume > avgVolume;

        if (current4hRegime == MarketRegime.TRENDING_UP) {
            boolean bullishCandle = cPrice > oPrice;

            if (nearEma && bullishCandle && strongVolume && isMacdBullish) { 
                log.info("[{}] Trigger detected! Pullback near 50-EMA with STRONG VOLUME & MACD Bullish.", getName());
                
                // Stop Loss Dinâmico 3.0x o ATR abaixo da entrada
                double stopLoss = cPrice - (currentAtr * 3.0);
                double target = cPrice + ((cPrice - stopLoss) * 5.0);

                double quantity = riskManagerService.calculatePositionSize(cPrice, stopLoss);
                return TradeSignal.enter(TradeSide.LONG, quantity, stopLoss, target, 2.0, null);
            }
        } else if (current4hRegime == MarketRegime.TRENDING_DOWN) {
            boolean bearishCandle = cPrice < oPrice;
            
            if (nearEma && bearishCandle && strongVolume && isMacdBearish) { 
                log.info("[{}] Trigger detected! Rejection near 50-EMA with STRONG VOLUME & MACD Bearish.", getName());
                
                // Stop Loss Dinâmico 3.0x o ATR acima da entrada
                double stopLoss = cPrice + (currentAtr * 1.5);
                double target = cPrice - ((stopLoss - cPrice) * 5.0);
                
                double quantity = riskManagerService.calculatePositionSize(cPrice, stopLoss);
                return TradeSignal.enter(TradeSide.SHORT, quantity, stopLoss, target, 2.0, null);
            }
        }

        return TradeSignal.ignore();
    }
}
