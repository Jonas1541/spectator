package com.jonasdurau.spectator.core.service;

import com.jonasdurau.spectator.integration.binance.BinanceAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Provedor de equity que consulta o saldo real da conta Binance Futures.
 * Delega para BinanceAccountService que já implementa cache de 30s.
 * Ativado apenas quando live trading está habilitado.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class LiveAccountEquityProvider implements AccountEquityProvider {

    private static final Logger log = LoggerFactory.getLogger(LiveAccountEquityProvider.class);

    private final BinanceAccountService binanceAccountService;

    public LiveAccountEquityProvider(BinanceAccountService binanceAccountService) {
        this.binanceAccountService = binanceAccountService;
        log.info("💰 Live Account Equity Provider active. Using real Binance Futures balance.");
    }

    @Override
    public double getAccountEquity() {
        double balance = binanceAccountService.getAvailableBalance();
        log.debug("Live account equity: {} USDT", balance);
        return balance;
    }
}
