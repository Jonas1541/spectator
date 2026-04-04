package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO para deserializar a resposta de GET /fapi/v2/balance da Binance Futures.
 * Cada elemento do array retornado representa o saldo de um ativo específico.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceBalanceResponse(
        String asset,
        String balance,
        String availableBalance,
        String crossUnPnl
) {}
