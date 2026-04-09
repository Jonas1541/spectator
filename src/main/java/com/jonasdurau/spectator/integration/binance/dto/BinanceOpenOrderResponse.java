package com.jonasdurau.spectator.integration.binance.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO para deserializar a resposta de GET /fapi/v1/openOrders da Binance Futures.
 * Contém os campos necessários para reconciliação de ordens ao reiniciar o bot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BinanceOpenOrderResponse(
        Long orderId,
        String symbol,
        String status,
        String type,
        String side,
        String stopPrice
) {}
