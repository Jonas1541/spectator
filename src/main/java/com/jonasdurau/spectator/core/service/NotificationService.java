package com.jonasdurau.spectator.core.service;

/**
 * Abstração para notificações do motor de trading (satisfaz DIP e OCP).
 * Permite extensão futura para outros canais (Discord, Email, etc.)
 * sem modificar os serviços que consomem notificações.
 */
public interface NotificationService {

    /**
     * Notifica sobre uma nova entrada de posição.
     */
    void notifyTradeEntry(String symbol, String strategy, String side, double price, double quantity);

    /**
     * Notifica sobre uma saída de posição (SL, TP, Panic Close, etc.).
     * @param exitReason Ex: "STOP_LOSS", "TAKE_PROFIT", "PANIC_CLOSE", "MANUAL"
     */
    void notifyTradeExit(String symbol, String exitReason, String side, double price, double pnl);

    /**
     * Notifica sobre um erro crítico no sistema.
     */
    void notifyCriticalError(String context, String errorMessage);
}
