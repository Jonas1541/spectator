package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.integration.binance.dto.BinanceBalanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Serviço que consulta informações da conta na Binance Futures.
 * Usa o RestClient autenticado com X-MBX-APIKEY e assina os parâmetros com HMAC-SHA256.
 * O saldo é cacheado por 30 segundos para evitar chamadas excessivas à API.
 * Ativado apenas quando live trading está habilitado.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class BinanceAccountService {

    private static final Logger log = LoggerFactory.getLogger(BinanceAccountService.class);
    private static final long CACHE_TTL_MS = 30_000; // 30 segundos de cache
    private static final String TARGET_ASSET = "USDT";

    private final RestClient authenticatedApi;
    private final BinanceApiSigner signer;

    // Cache de equity para evitar chamadas excessivas
    private final AtomicReference<CachedBalance> cachedBalance = new AtomicReference<>(new CachedBalance(0.0, 0L));

    public BinanceAccountService(@Qualifier("binanceAuthenticatedApi") RestClient authenticatedApi,
                                  BinanceApiSigner signer) {
        this.authenticatedApi = authenticatedApi;
        this.signer = signer;
    }

    /**
     * Retorna o saldo USDT disponível na conta Binance Futures.
     * Usa cache de 30s para evitar rate limiting.
     */
    public double getAvailableBalance() {
        CachedBalance cached = cachedBalance.get();
        long now = Instant.now().toEpochMilli();

        if (now - cached.timestamp() < CACHE_TTL_MS) {
            return cached.balance();
        }

        double freshBalance = fetchBalanceFromBinance();
        cachedBalance.set(new CachedBalance(freshBalance, now));
        return freshBalance;
    }

    private double fetchBalanceFromBinance() {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        try {
            List<BinanceBalanceResponse> balances = authenticatedApi.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/fapi/v2/balance")
                            .queryParam("timestamp", timestamp)
                            .queryParam("signature", signature)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (balances == null || balances.isEmpty()) {
                log.warn("No balance data returned from Binance. Returning 0.");
                return 0.0;
            }

            return balances.stream()
                    .filter(b -> TARGET_ASSET.equals(b.asset()))
                    .findFirst()
                    .map(b -> Double.parseDouble(b.availableBalance()))
                    .orElseGet(() -> {
                        log.warn("USDT balance not found in Binance response.");
                        return 0.0;
                    });
        } catch (Exception e) {
            log.error("Failed to fetch balance from Binance: {}", e.getMessage());
            // Em caso de falha, retorna o último valor cacheado se disponível
            CachedBalance cached = cachedBalance.get();
            if (cached.balance() > 0) {
                log.warn("Returning last cached balance: {}", cached.balance());
                return cached.balance();
            }
            return 0.0;
        }
    }

    private record CachedBalance(double balance, long timestamp) {}
}
