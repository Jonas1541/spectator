package com.jonasdurau.spectator.integration.binance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Utilitário para assinar requisições à API privada da Binance usando HMAC-SHA256.
 * A Binance exige que todos os endpoints privados tenham um parâmetro 'signature'
 * que é o HMAC-SHA256 da query string completa usando o API Secret.
 * Ativado apenas quando live trading está habilitado.
 */
@Component
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class BinanceApiSigner {

    private static final Logger log = LoggerFactory.getLogger(BinanceApiSigner.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final String apiSecret;

    public BinanceApiSigner(@Value("${spectator.binance.api-secret}") String apiSecret) {
        this.apiSecret = apiSecret;
        if (apiSecret == null || apiSecret.isBlank()) {
            log.error("🚨 BINANCE_API_SECRET is not configured! Live trading will fail.");
        }
    }

    /**
     * Gera a assinatura HMAC-SHA256 para a query string fornecida.
     * @param queryString A query string completa (ex: "symbol=BTCUSDT&timestamp=1234567890")
     * @return A assinatura em hexadecimal lowercase
     */
    public String sign(String queryString) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign Binance API request", e);
        }
    }
}
