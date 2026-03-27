package com.jonasdurau.spectator.core.strategy;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import com.jonasdurau.spectator.core.domain.TradeSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
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
    private static final double BB_MULTIPLIER = 2.0;
    
    // O "Filtro de Tédio": A distância entre as bandas não pode ser maior que 5% do preço
    private static final double MAX_SQUEEZE_WIDTH_PCT = 0.05; 

    public BollingerBreakoutStrategy() {}

    @Override
    public String getName() {
        return "1H BB Volatility Breakout";
    }

    @Override
    public TradeSignal evaluate(List<Candle> recent1hCandles, MarketRegime current4hRegime, double currentPrice, OrderFlowContext orderFlowContext) {
        // 1. O Filtro de Regime: Só procuramos rompimentos de caixote em mercados laterais
        if (current4hRegime != MarketRegime.SIDEWAYS) {
            return TradeSignal.ignore();
        }

        if (recent1hCandles.size() <= BB_PERIOD + 1) {
            return TradeSignal.ignore();
        }

        BarSeries series = Ta4jMapper.toBarSeries(recent1hCandles, "Crypto_1H");
        int endIndex = series.getEndIndex();
        int prevIndex = endIndex - 1;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSma = new SMAIndicator(volume, BB_PERIOD);

        SMAIndicator sma20 = new SMAIndicator(closePrice, BB_PERIOD);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, BB_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma20);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, series.numFactory().numOf(BB_MULTIPLIER));
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev, series.numFactory().numOf(BB_MULTIPLIER));

        // 2. Aferição do Squeeze (Aperto) na vela anterior
        double prevUpper = bbUpper.getValue(prevIndex).doubleValue();
        double prevLower = bbLower.getValue(prevIndex).doubleValue();
        double prevMiddle = bbMiddle.getValue(prevIndex).doubleValue();
        
        // Largura do canal em percentagem (ex: 0.03 = 3%)
        double prevBandWidthPct = (prevUpper - prevLower) / prevMiddle;

        if (prevBandWidthPct > MAX_SQUEEZE_WIDTH_PCT) {
            return TradeSignal.ignore(); // O mercado está lateral, mas as bandas estão muito largas (violino).
        }

        // 3. Aferição da Ação de Preço Atual (Rompimento + Institucionais)
        double cPrice = closePrice.getValue(endIndex).doubleValue();
        double currentLower = bbLower.getValue(endIndex).doubleValue();
        double currentUpper = bbUpper.getValue(endIndex).doubleValue();
        
        double currentVol = volume.getValue(endIndex).doubleValue();
        double avgVol = volumeSma.getValue(endIndex).doubleValue();
        
        // Exige que o volume do rompimento seja pelo menos 50% maior que a média recente
        boolean isHighVolumeBreakout = currentVol > (avgVol * 1.5); 

        // === LONG: Preço fechou acima do "Caixote" ===
        if (cPrice > currentUpper && isHighVolumeBreakout) {
            
            if (orderFlowContext != null && orderFlowContext.cumulativeVolumeDelta() < 0) {
                log.info("[{}] Falso Rompimento de Alta evitado (CVD Negativo).", getName());
                return TradeSignal.ignore();
            }

            // O Stop Loss fica do outro lado do caixote (Banda Inferior) para invalidar a tese
            double stopLoss = currentLower; 
            double risk = cPrice - stopLoss;
            if (risk <= 0) return TradeSignal.ignore();
            
            double takeProfit = cPrice + (risk * 1.5);

            log.info("[{}] LONG BREAKOUT! Preço ({}) rompeu BB. Squeeze: {}%. Volume: {}x. SL: {}, TP: {}", 
                     getName(), String.format("%.2f", cPrice), String.format("%.1f", prevBandWidthPct * 100), 
                     String.format("%.1f", currentVol/avgVol), String.format("%.2f", stopLoss), String.format("%.2f", takeProfit));

            // Entra, realiza 50% de lucro (0.50) e move para Breakeven se o preço andar 1.0x o risco
            return TradeSignal.enter(TradeSide.LONG, stopLoss, takeProfit, 1.0, null, 0.50);
        }

        // === SHORT: Preço fechou abaixo do "Caixote" ===
        if (cPrice < currentLower && isHighVolumeBreakout) {
            
            if (orderFlowContext != null && orderFlowContext.cumulativeVolumeDelta() > 0) {
                log.info("[{}] Falso Rompimento de Baixa evitado (CVD Positivo).", getName());
                return TradeSignal.ignore();
            }

            // O Stop Loss fica no topo do caixote (Banda Superior)
            double stopLoss = currentUpper; 
            double risk = stopLoss - cPrice;
            if (risk <= 0) return TradeSignal.ignore();
            
            double takeProfit = cPrice - (risk * 1.5);

            log.info("[{}] SHORT BREAKOUT! Preço ({}) rompeu BB. Squeeze: {}%. Volume: {}x. SL: {}, TP: {}", 
                     getName(), String.format("%.2f", cPrice), String.format("%.1f", prevBandWidthPct * 100), 
                     String.format("%.1f", currentVol/avgVol), String.format("%.2f", stopLoss), String.format("%.2f", takeProfit));

            return TradeSignal.enter(TradeSide.SHORT, stopLoss, takeProfit, 1.0, null, 0.50);
        }

        return TradeSignal.ignore();
    }
}