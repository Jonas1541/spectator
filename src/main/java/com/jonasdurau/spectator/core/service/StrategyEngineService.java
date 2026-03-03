package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
import com.jonasdurau.spectator.core.domain.TradeSide;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StrategyEngineService {

    private static final Logger log = LoggerFactory.getLogger(StrategyEngineService.class);

    private final OrderExecutionService orderExecutionService;
    private final PositionRepository positionRepository;

    private static final double STANDARD_QTY = 0.1;
    private static final double STOP_LOSS_PCT = 0.05; // 5%
    private static final double TAKE_PROFIT_PCT = 0.10; // 10%

    public StrategyEngineService(OrderExecutionService orderExecutionService, PositionRepository positionRepository) {
        this.orderExecutionService = orderExecutionService;
        this.positionRepository = positionRepository;
    }

    public void processTick(String symbol, double currentPrice, MarketRegime regime) {
        List<Position> openPositions = positionRepository.findBySymbolAndStatus(symbol, PositionStatus.OPEN);

        // Simples anti-martingale: 1 posição por vez
        if (!openPositions.isEmpty()) {
            return;
        }

        if (regime == MarketRegime.TRENDING_UP) {
            log.info("Strategy hit: TRENDING_UP -> Processing LONG signal");
            double sl = currentPrice * (1 - STOP_LOSS_PCT);
            double tp = currentPrice * (1 + TAKE_PROFIT_PCT);
            orderExecutionService.executeMarketOrder(symbol, TradeSide.LONG, STANDARD_QTY, currentPrice, sl, tp);
        } else if (regime == MarketRegime.TRENDING_DOWN) {
            log.info("Strategy hit: TRENDING_DOWN -> Processing SHORT signal");
            double sl = currentPrice * (1 + STOP_LOSS_PCT);
            double tp = currentPrice * (1 - TAKE_PROFIT_PCT);
            orderExecutionService.executeMarketOrder(symbol, TradeSide.SHORT, STANDARD_QTY, currentPrice, sl, tp);
        }
    }
}
