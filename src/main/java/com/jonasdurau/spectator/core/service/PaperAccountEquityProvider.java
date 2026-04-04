package com.jonasdurau.spectator.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Provedor de equity para paper trading / backtesting.
 * Retorna um valor fixo simulado de 200 USDT.
 * Ativado quando live trading NÃO está habilitado (fallback padrão).
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "false", matchIfMissing = true)
public class PaperAccountEquityProvider implements AccountEquityProvider {

    private static final Logger log = LoggerFactory.getLogger(PaperAccountEquityProvider.class);
    private static final double MOCK_EQUITY = 200.0;

    public PaperAccountEquityProvider() {
        log.info("📝 Paper Account Equity Provider active. Using simulated balance of {} USDT.", MOCK_EQUITY);
    }

    @Override
    public double getAccountEquity() {
        return MOCK_EQUITY;
    }
}
