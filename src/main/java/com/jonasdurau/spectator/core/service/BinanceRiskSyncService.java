package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.core.domain.Position;

/**
 * Abstração para sincronização de ordens de risco (SL/TP) com a exchange.
 * Satisfaz DIP: o PositionManagerService depende desta interface,
 * não da implementação concreta da Binance.
 * Implementações: LiveBinanceRiskSyncService (real) e NoOpBinanceRiskSyncService (paper/backtest).
 */
public interface BinanceRiskSyncService {

    /**
     * Coloca uma ordem STOP_MARKET na exchange para a posição dada.
     * @return orderId da exchange, ou null se não aplicável
     */
    Long placeStopLoss(Position position, double stopPrice);

    /**
     * Coloca uma ordem TAKE_PROFIT_MARKET na exchange para a posição dada.
     * @return orderId da exchange, ou null se não aplicável
     */
    Long placeTakeProfit(Position position, double tpPrice);

    /**
     * Cancela a ordem de Stop Loss existente na exchange.
     * Limpa o ID na Position.
     */
    void cancelStopLoss(Position position);

    /**
     * Cancela a ordem de Take Profit existente na exchange.
     * Limpa o ID na Position.
     */
    void cancelTakeProfit(Position position);

    /**
     * Cancela todas as ordens residuais (SL e TP) de uma posição na exchange.
     * Utilizado ao fechar posições por motivos que não sejam SL/TP da própria Binance.
     */
    void cancelAllOrders(Position position);
}
