package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO para deserializar posições abertas da Binance Futures (GET /fapi/v2/positionRisk).
 * Usado pelo LiveAccountSyncService para reconciliação ao iniciar o bot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinancePositionRiskResponse(
        String symbol,
        String positionAmt,
        String entryPrice,
        String unRealizedProfit,
        String liquidationPrice
) {}
