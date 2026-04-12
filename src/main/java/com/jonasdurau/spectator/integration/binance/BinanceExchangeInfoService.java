package com.jonasdurau.spectator.integration.binance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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
            JsonNode response = restClient.get()
                    .uri("https://fapi.binance.com/fapi/v1/exchangeInfo")
                    .retrieve()
                    .body(JsonNode.class);

            if (response != null && response.has("symbols")) {
                JsonNode symbols = response.get("symbols");
                for (JsonNode symbolNode : symbols) {
                    if (symbolNode.has("symbol") && symbolNode.has("quantityPrecision")) {
                        String symbol = symbolNode.get("symbol").asText();
                        int quantityPrecision = symbolNode.get("quantityPrecision").asInt();
                        symbolPrecisions.put(symbol, quantityPrecision);
                    }
                }
                log.info("Regras de precisão carregadas para {} símbolos.", symbolPrecisions.size());
            } else {
                log.warn("O campo 'symbols' não foi encontrado na resposta do exchangeInfo da Binance.");
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
