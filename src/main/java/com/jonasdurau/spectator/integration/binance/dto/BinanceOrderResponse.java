package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO para deserializar a resposta de POST /fapi/v1/order da Binance Futures.
 * Contém os dados essenciais da ordem executada.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceOrderResponse(
        Long orderId,
        String symbol,
        String status,
        String side,
        String type,
        String avgPrice,
        String executedQty,
        String origQty,
        String clientOrderId
) {}
