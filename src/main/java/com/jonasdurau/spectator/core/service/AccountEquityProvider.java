package com.jonasdurau.spectator.core.service;

/**
 * Abstração para fornecer o equity da conta de trading (satisfaz DIP).
 * Permite que o StrategyEngineService funcione tanto com saldo real
 * quanto com saldo simulado, sem conhecer a fonte concreta.
 */
public interface AccountEquityProvider {

    /**
     * Retorna o equity disponível para trading em USDT.
     */
    double getAccountEquity();
}
