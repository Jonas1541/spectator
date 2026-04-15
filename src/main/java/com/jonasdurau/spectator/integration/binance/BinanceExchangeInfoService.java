package com.jonasdurau.spectator.integration.binance;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serviço responsável por buscar e gerenciar as regras e metadados da Binance.
 * Mantém em cache a precisão exigida para as quantidades das ordens (quantityPrecision),
 * e para os preços (pricePrecision), e os valores mínimos de nocional,
 * evitando erros de validação como "Precision is over the maximum defined" ou "Order's notional must be no smaller than X".
 */
@Service
public class BinanceExchangeInfoService {

    private static final Logger log = LoggerFactory.getLogger(BinanceExchangeInfoService.class);

    private final RestClient restClient;
    private final ConcurrentHashMap<String, Integer> quantityPrecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pricePrecisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> minNotionals = new ConcurrentHashMap<>();

    public BinanceExchangeInfoService(@Qualifier("binanceApi") RestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    public void init() {
        log.info("Buscando exchange info da Binance para carregar regras de precisão e notional...");
        try {
            BinanceExchangeInfoResponse response = restClient.get()
                    .uri("https://fapi.binance.com/fapi/v1/exchangeInfo")
                    .retrieve()
                    .body(BinanceExchangeInfoResponse.class);

            if (response != null && response.symbols() != null) {
                for (BinanceSymbolInfo symbolInfo : response.symbols()) {
                    if (symbolInfo.symbol() != null) {
                        quantityPrecisions.put(symbolInfo.symbol(), symbolInfo.quantityPrecision());
                        pricePrecisions.put(symbolInfo.symbol(), symbolInfo.pricePrecision());
                        
                        if (symbolInfo.filters() != null) {
                            for (BinanceSymbolFilter filter : symbolInfo.filters()) {
                                if ("MIN_NOTIONAL".equals(filter.filterType()) && filter.notional() != null) {
                                    minNotionals.put(symbolInfo.symbol(), Double.parseDouble(filter.notional()));
                                }
                            }
                        }
                    }
                }
                log.info("Regras carregadas para {} símbolos.", quantityPrecisions.size());
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
        return quantityPrecisions.getOrDefault(symbol, 0);
    }

    /**
     * Retorna a precisão permitida para o preço em um determinado símbolo.
     * Caso não encontre, o fallback padrão será 2.
     *
     * @param symbol o nome do par (ex: SOLUSDT)
     * @return o número de casas decimais suportados
     */
    public int getPricePrecision(String symbol) {
        return pricePrecisions.getOrDefault(symbol, 2);
    }

    /**
     * Retorna o valor mínimo nocional (quantidade * preço) exigido para a ordem.
     *
     * @param symbol o nome do par (ex: BTCUSDT)
     * @return o mínimo estipulado pela corretora (fallback padrão: 5.0)
     */
    public double getMinNotional(String symbol) {
        return minNotionals.getOrDefault(symbol, 5.0);
    }

    /**
     * Sanitiza um preço bruto para o formato aceito pela Binance.
     * 1. Arredonda para a precisão decimal exigida pelo símbolo.
     * 2. Aplica Regra de Sobrevivência (Min Price): se o preço arredondado for <= 0,
     *    força para o menor valor representável pela precisão (ex: precisão 3 → 0.001).
     * 3. Retorna como String sem notação científica (toPlainString).
     *
     * @param symbol   o par (ex: NEARUSDT)
     * @param rawPrice o preço bruto calculado
     * @return o preço sanitizado pronto para a API, em formato plain string
     */
    public String sanitizePrice(String symbol, double rawPrice) {
        int precision = getPricePrecision(symbol);
        BigDecimal rounded = BigDecimal.valueOf(rawPrice)
                .setScale(precision, RoundingMode.HALF_UP);

        // Regra de Sobrevivência: preço <= 0 é inválido na Binance
        if (rounded.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal minPrice = BigDecimal.ONE.movePointLeft(precision);
            log.warn("⚠️ Price {} for {} rounded to {} (<=0). Forcing to min price: {}",
                    rawPrice, symbol, rounded.toPlainString(), minPrice.toPlainString());
            rounded = minPrice;
        }

        return rounded.stripTrailingZeros().toPlainString();
    }
}

record BinanceSymbolFilter(String filterType, String notional) {}
record BinanceExchangeInfoResponse(List<BinanceSymbolInfo> symbols) {}
record BinanceSymbolInfo(String symbol, int quantityPrecision, int pricePrecision, List<BinanceSymbolFilter> filters) {}
