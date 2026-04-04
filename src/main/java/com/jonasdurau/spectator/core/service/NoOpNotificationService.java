package com.jonasdurau.spectator.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementação silenciosa de NotificationService.
 * Ativada quando Telegram está desabilitado (default).
 * Apenas loga em TRACE que as notificações estão desabilitadas.
 */
@Service
@ConditionalOnProperty(name = "spectator.telegram.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NoOpNotificationService.class);

    public NoOpNotificationService() {
        log.info("🔕 Notifications disabled. Using NoOp notification service.");
    }

    @Override
    public void notifyTradeEntry(String symbol, String strategy, String side, double price, double quantity) {
        log.trace("NoOp: Trade entry notification suppressed for {} {} {}", strategy, side, symbol);
    }

    @Override
    public void notifyTradeExit(String symbol, String exitReason, String side, double price, double pnl) {
        log.trace("NoOp: Trade exit notification suppressed for {} {}", exitReason, symbol);
    }

    @Override
    public void notifyCriticalError(String context, String errorMessage) {
        log.trace("NoOp: Critical error notification suppressed for {}", context);
    }
}
