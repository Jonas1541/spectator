package com.jonasdurau.spectator.integration.binance;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serviço responsável por buscar e gerenciar as regras e metadados da Binance.
 * Mantém em cache a precisão exigida para as quantidades das ordens (quantityPrecision),
 * evitando erros como "Precision is over the maximum defined for this asset".
 */
@Service
public class BinanceExchangeInfoService {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeInfoService.class);

    private final RestClient restClient;
    private final ConcurrentHashMap<String, Integer> symbolPrecisions = new ConcurrentHashMap<>();

    public BinanceExchangeInfoService(@Qualifier("binanceApi") RestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    public void init() {
        log.info("Buscando exchange info da Binance para carregar regras de precisão...");
        try {
            BinanceExchangeInfoResponse response = restClient.get()
                    .uri("https://fapi.binance.com/fapi/v1/exchangeInfo")
                    .retrieve()
                    .body(BinanceExchangeInfoResponse.class);

            if (response != null && response.symbols() != null) {
                for (BinanceSymbolInfo symbolInfo : response.symbols()) {
                    if (symbolInfo.symbol() != null) {
                        symbolPrecisions.put(symbolInfo.symbol(), symbolInfo.quantityPrecision());
                    }
                }
                log.info("Regras de precisão carregadas para {} símbolos.", symbolPrecisions.size());
            } else {
                log.warn("O campo 'symbols' não foi encontrado ou está vazio na resposta do exchangeInfo da Binance.");
            }
        } catch (Exception e) {
            log.error("Falha ao buscar exchange info da Binance: {}", e.getMessage(), e);
        }
    }

    /**
     * Retorna a precisão permitida para a quantidade em um determinado símbolo.
     * Caso não encontre, o fallback padrão será 0.
     *
     * @param symbol o nome do par (ex: SOLUSDT)
     * @return o número de casas decimais suportados
     */
    public int getQuantityPrecision(String symbol) {
        return symbolPrecisions.getOrDefault(symbol, 0);
    }
}

record BinanceExchangeInfoResponse(List<BinanceSymbolInfo> symbols) {}
record BinanceSymbolInfo(String symbol, int quantityPrecision) {}
