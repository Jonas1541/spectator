package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
import com.jonasdurau.spectator.core.domain.OrderFlowContext;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import com.jonasdurau.spectator.core.strategy.TradeSignal;
import com.jonasdurau.spectator.core.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StrategyEngineService {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngineService.class);

    private final OrderExecutionService orderExecutionService;
    private final PositionRepository positionRepository;
    private final RiskManagerService riskManagerService;
    private final OrderFlowService orderFlowService;
    private final List<TradingStrategy> strategies;

    public StrategyEngineService(OrderExecutionService orderExecutionService, 
                                 PositionRepository positionRepository,
                                 RiskManagerService riskManagerService,
                                 OrderFlowService orderFlowService,
                                 List<TradingStrategy> strategies) {
        this.orderExecutionService = orderExecutionService;
        this.positionRepository = positionRepository;
        this.riskManagerService = riskManagerService;
        this.orderFlowService = orderFlowService;
        this.strategies = strategies;
    }

    public void processTick(String symbol, double currentPrice, MarketRegime regime, List<Candle> recent1hCandles) {
        List<Position> openPositions = positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);

        // Simples anti-martingale: 1 posição por vez no painel global
        if (!openPositions.isEmpty()) {
            return;
        }

        OrderFlowContext orderFlowContext = orderFlowService.getSnapshot(symbol);

        for (TradingStrategy strategy : strategies) {
            TradeSignal signal = strategy.evaluate(recent1hCandles, regime, currentPrice, orderFlowContext);
            
            if (signal.fire()) {
                // --- DYNAMIC WIN RATE ENGINE ---
                int totalClosed = positionRepository.countByStrategyNameAndStatus(strategy.getName(), PositionStatus.CLOSED);
                double dynamicWinProb;
                if (totalClosed < 5) {
                    dynamicWinProb = 0.40; // Base cautious probability if not enough data
                    log.info("Strategy [{}] has under 5 trades. Defaulting to 40% win rate.", strategy.getName());
                } else {
                    int totalWinners = positionRepository.countByStrategyNameAndStatusAndRealizedPnlGreaterThan(strategy.getName(), PositionStatus.CLOSED, 0.0);
                    dynamicWinProb = (double) totalWinners / totalClosed;
                    log.info("Strategy [{}] Hit Rate Engine: {}/{} wins ({}%).", strategy.getName(), totalWinners, totalClosed, String.format("%.2f", dynamicWinProb * 100));
                }
                
                // MOCK Exposure calculation
                double MOCK_ACCOUNT_EQUITY = 10000.0;
                double currentExposure = 0.0;
                for (Position p : openPositions) {
                    currentExposure += (p.getQuantity() * p.getEntryPrice());
                }
                double currentExposurePct = currentExposure / MOCK_ACCOUNT_EQUITY;

                double quantity = riskManagerService.calculateKellyPositionSize(
                        currentPrice, 
                        signal.stopLoss(), 
                        signal.takeProfit(), 
                        dynamicWinProb, 
                        MOCK_ACCOUNT_EQUITY, 
                        currentExposurePct
                );

                if (quantity <= 0.0) {
                    log.info("Strategy [{}] fired but RiskManager rejected the trade (Size 0).", strategy.getName());
                    return;
                }

                log.info("Strategy [{}] fired {}! Executing size: {}", strategy.getName(), signal.side(), quantity);
                orderExecutionService.executeMarketOrder(
                        strategy.getName(),
                        symbol, 
                        signal.side(), 
                        quantity, 
                        currentPrice, 
                        signal.stopLoss(), 
                        signal.takeProfit(),
                        signal.breakevenMultiplier(),
                        signal.trailingMultiplier(),
                        signal.tp1Price(),
                        signal.tp1SizePct()
                );
                // Return immediately so we don't open overlapping trades inside the same iteration
                return;
            }
        }
    }
}
