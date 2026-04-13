package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO para deserializar a resposta de POST /fapi/v1/algoOrder da Binance Futures.
 * Desde Dezembro/2025, ordens condicionais (STOP_MARKET, TAKE_PROFIT_MARKET)
 * foram migradas para a Algo Order API e retornam algoId ao invés de orderId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceAlgoOrderResponse(
        Long algoId,
        String clientAlgoId,
        String algoType,
        String orderType,
        String symbol,
        String side,
        String positionSide,
        String algoStatus,
        String triggerPrice,
        boolean closePosition
) {}
