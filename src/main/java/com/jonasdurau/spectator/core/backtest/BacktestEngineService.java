package com.jonasdurau.spectator.core.backtest;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.strategy.RegimeAnalyzerService;
import com.jonasdurau.spectator.core.strategy.TradeSignal;
import com.jonasdurau.spectator.core.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class BacktestEngineService {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngineService.class);

    private final CandleRepository candleRepository;
    private final RegimeAnalyzerService regimeAnalyzerService;

    // Precisamos de no mínimo 200 candles de "warmup" para as médias móveis existirem
    private static final int WARMUP_PERIOD = 200; 

    public BacktestEngineService(CandleRepository candleRepository, RegimeAnalyzerService regimeAnalyzerService) {
        this.candleRepository = candleRepository;
        this.regimeAnalyzerService = regimeAnalyzerService;
    }

    public BacktestReport runBacktest(TradingStrategy strategy, String symbol, Instant start, Instant end, double initialCapital) {
        log.info("Starting backtest for {} on {} from {} to {}", strategy.getName(), symbol, start, end);

        // 1. Carrega os dados históricos ordenados (Do mais antigo para o mais novo)
        List<Candle> history1h = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "1h", start, end);
        List<Candle> history4h = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc(symbol, "4h", start, end);

        if (history1h.size() <= WARMUP_PERIOD) {
            throw new IllegalStateException("Not enough historical data for backtest. Found: " + history1h.size());
        }

        // Variáveis de Controle de Capital e Estatística
        double currentCapital = initialCapital;
        double peakCapital = initialCapital;
        double maxDrawdown = 0.0;
        
        int winningTrades = 0;
        int losingTrades = 0;

        //A lista que vai guardar as setinhas!
        java.util.List<BacktestTrade> tradeLog = new java.util.ArrayList<>();

        // Estado do Trade Atual
        boolean inPosition = false;
        TradeSide currentSide = null;
        double entryPrice = 0.0;
        double positionQuantity = 0.0;
        double stopLoss = 0.0;
        double takeProfit = 0.0;

        // 2. O Loop do Tempo (Começamos depois do período de aquecimento)
        for (int i = WARMUP_PERIOD; i < history1h.size(); i++) {
            Candle currentCandle = history1h.get(i);

            // --- AVALIAÇÃO DE SAÍDA (Se já estivermos posicionados) ---
            if (inPosition) {
                boolean closed = false;
                double exitPrice = 0.0;

                // Checagem conservadora: Se a mínima do candle pegar o SL, assumimos o pior cenário primeiro.
                if (currentSide == TradeSide.LONG) {
                    if (currentCandle.getLow() <= stopLoss) {
                        exitPrice = stopLoss;
                        closed = true;
                    } else if (currentCandle.getHigh() >= takeProfit) {
                        exitPrice = takeProfit;
                        closed = true;
                    }
                } else { // SHORT
                    if (currentCandle.getHigh() >= stopLoss) {
                        exitPrice = stopLoss;
                        closed = true;
                    } else if (currentCandle.getLow() <= takeProfit) {
                        exitPrice = takeProfit;
                        closed = true;
                    }
                }

                if (closed) {
                    double pnl = (currentSide == TradeSide.LONG) ? 
                                 (exitPrice - entryPrice) * positionQuantity : 
                                 (entryPrice - exitPrice) * positionQuantity;
                                 
                    currentCapital += pnl;
                    
                    if (pnl > 0) winningTrades++;
                    else losingTrades++;

                    // Atualiza Drawdown
                    if (currentCapital > peakCapital) {
                        peakCapital = currentCapital;
                    } else {
                        double drawdown = ((peakCapital - currentCapital) / peakCapital) * 100;
                        if (drawdown > maxDrawdown) maxDrawdown = drawdown;
                    }

                    //Registramos a Saída (Close)
                    tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, false, exitPrice, pnl));
                    inPosition = false;
                }
                continue; // Se estamos em posição, não abrimos outra (Anti-martingale)
            }

            // --- AVALIAÇÃO DE ENTRADA (Se estivermos líquidos) ---
            
            // Recorta a "janela" de candles como se estivéssemos naquele exato momento do passado
            List<Candle> window1h = history1h.subList(i - WARMUP_PERIOD, i + 1);
            
            // Encontra quais candles de 4H pertencem ao passado até este momento para calcular o Regime
            List<Candle> window4h = history4h.stream()
                    .filter(c -> !c.getTime().isAfter(currentCandle.getTime()))
                    .toList();

            MarketRegime regime = MarketRegime.SIDEWAYS;
            // Pegamos apenas os últimos 250 de 4H para não sobrecarregar a memória
            if (window4h.size() > 250) {
                window4h = window4h.subList(window4h.size() - 250, window4h.size());
            }
            if (window4h.size() > 50) {
                regime = regimeAnalyzerService.analyze(window4h);
            }

            // O Robô pensa e emite o sinal
            TradeSignal signal = strategy.evaluate(window1h, regime, currentCandle.getClose());

            if (signal.fire()) {
                inPosition = true;
                currentSide = signal.side();
                entryPrice = currentCandle.getClose();
                stopLoss = signal.stopLoss();
                takeProfit = signal.takeProfit();
                
                // Em backtest, para ter métricas limpas, vamos padronizar o risco em 1% do capital ATUAL por trade
                double riskAmount = currentCapital * 0.01;
                double stopDistance = Math.abs(entryPrice - stopLoss);
                positionQuantity = riskAmount / stopDistance;

                //Registramos a Entrada (Open)
                tradeLog.add(new BacktestTrade(currentCandle.getTime(), currentSide, true, entryPrice, 0.0));
            }
        }

        // 3. Monta o Relatório Final
        int totalTrades = winningTrades + losingTrades;
        double winRate = totalTrades > 0 ? ((double) winningTrades / totalTrades) * 100 : 0.0;
        double netProfit = currentCapital - initialCapital;

        return new BacktestReport(
                strategy.getName(), symbol, totalTrades, winningTrades, losingTrades, 
                winRate, netProfit, maxDrawdown, initialCapital, currentCapital, tradeLog
        );
    }
}