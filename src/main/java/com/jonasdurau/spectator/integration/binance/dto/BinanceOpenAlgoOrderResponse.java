package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO para deserializar a resposta de GET /fapi/v1/openAlgoOrders da Binance Futures.
 * Usado na reconciliação para verificar se ordens condicionais (SL/TP) ainda estão ativas.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceOpenAlgoOrderResponse(
        Long algoId,
        String symbol,
        String algoStatus,
        String orderType,
        String side
) {}
