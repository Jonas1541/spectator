package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.service.RiskManagerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.LowestValueIndicator;

import java.util.List;

@Component
public class PullbackTrendStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PullbackTrendStrategy.class);

    private static final int EMA_50 = 50;
    private static final int SWING_LOW_PERIOD = 5;
    
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
        // Only active on Trending limits
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
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        EMAIndicator ema50 = new EMAIndicator(closePrice, EMA_50);
        
        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double oPrice = openPrice.getValue(endIndex).doubleValue();
        double e50 = ema50.getValue(endIndex).doubleValue();

        double distanceToEma = Math.abs((cPrice - e50) / e50);

        if (current4hRegime == MarketRegime.TRENDING_UP) {
            // Regra 1: Pullback próximo da EMA-50
            boolean nearEma = distanceToEma <= MAX_PULLBACK_DISTANCE_PCT;
            
            // Regra 2: Candle de confirmação fechando em alta
            boolean bullishCandle = cPrice > oPrice;
            
            // Regra 3: O preço deve estar recuando (fechamento anterior menor que a abertura) e agora subindo,
            // ou ao menos garantindo as regras 1 e 2.
            if (nearEma && bullishCandle) {
                log.info("[{}] Trigger detected! Pullback near 50-EMA on 1H map.", getName());
                
                // Stop Loss no menor fundo dos últimos 5 candles
                LowestValueIndicator swingLowIndicator = new LowestValueIndicator(lowPrice, SWING_LOW_PERIOD);
                double stopLoss = swingLowIndicator.getValue(endIndex).doubleValue();
                
                // Ajuste de segurança caso o candle atual já seja o menor fundo
                if (stopLoss >= cPrice) {
                    stopLoss = cPrice * 0.98; // 2% fixed fallback
                }

                // Alvo com RR de 1:2
                double target = cPrice + ((cPrice - stopLoss) * 2);

                double quantity = riskManagerService.calculatePositionSize(cPrice, stopLoss);
                return TradeSignal.enter(TradeSide.LONG, quantity, stopLoss, target);
            }
        } else if (current4hRegime == MarketRegime.TRENDING_DOWN) {
            // Em uma tendência de baixa, buscamos um ressalto na EMA 50 e rejeição para baixo.
            // Regra 1: Próximo a EMA-50
            boolean nearEma = distanceToEma <= MAX_PULLBACK_DISTANCE_PCT;
            
            // Regra 2: Candle fechando em queda
            boolean bearishCandle = cPrice < oPrice;
            
            if (nearEma && bearishCandle) {
                log.info("[{}] Trigger detected! Rejection near 50-EMA on 1H map.", getName());
                
                // Stop Loss um pouco acima da EMA (margem de segurança) ou do topo anterior.
                double stopLoss = e50 * 1.01; // 1% gap
                
                if (stopLoss <= cPrice) {
                    stopLoss = cPrice * 1.02; // 2% fixed fallback
                }
                
                double target = cPrice - ((stopLoss - cPrice) * 2);
                double quantity = riskManagerService.calculatePositionSize(cPrice, stopLoss);
                return TradeSignal.enter(TradeSide.SHORT, quantity, stopLoss, target);
            }
        }

        return TradeSignal.ignore();
    }
}
