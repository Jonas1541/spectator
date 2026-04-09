package com.jonasdurau.spectator.integration.telegram;

import com.jonasdurau.spectator.core.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Implementação de NotificationService que envia mensagens via Telegram Bot API.
 * Usa POST https://api.telegram.org/bot{token}/sendMessage com parse_mode=Markdown.
 * Ativado apenas quando spectator.telegram.enabled=true.
 *
 * Mensagens formatadas com emojis para identificação visual rápida no celular.
 */
@Service
@ConditionalOnProperty(name = "spectator.telegram.enabled", havingValue = "true")
public class TelegramNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final RestClient telegramApi;
    private final String chatId;

    public TelegramNotificationService(
            @Value("${spectator.telegram.bot-token}") String botToken,
            @Value("${spectator.telegram.chat-id}") String chatId) {
        this.chatId = chatId;
        this.telegramApi = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + botToken)
                .build();

        if (botToken.isBlank() || chatId.isBlank()) {
            log.error("🚨 Telegram is enabled but bot-token or chat-id is empty! Notifications will fail.");
        } else {
            log.info("📱 Telegram Notification Service active. Chat ID: {}", chatId);
        }
    }

    @Override
    public void notifyTradeEntry(String symbol, String strategy, String side, double price, double quantity) {
        String emoji = "LONG".equalsIgnoreCase(side) ? "📈" : "📉";
        String message = String.format(
                "%s *ENTRY* | `%s`\n" +
                "Strategy: `%s`\n" +
                "Side: *%s*\n" +
                "Price: `%.2f`\n" +
                "Quantity: `%.6f`",
                emoji, symbol, strategy, side, price, quantity
        );
        sendMessage(message);
    }

    @Override
    public void notifyTradeExit(String symbol, String exitReason, String side, double price, double pnl) {
        String emoji = resolveExitEmoji(exitReason, pnl);
        String pnlFormatted = pnl >= 0 ? String.format("+$%.2f", pnl) : String.format("-$%.2f", Math.abs(pnl));
        String message = String.format(
                "%s *%s* | `%s`\n" +
                "Side: *%s*\n" +
                "Exit Price: `%.2f`\n" +
                "PnL: *%s*",
                emoji, exitReason, symbol, side, price, pnlFormatted
        );
        sendMessage(message);
    }

    @Override
    public void notifyCriticalError(String context, String errorMessage) {
        String message = String.format(
                "🚨 *CRITICAL ERROR*\n" +
                "Context: `%s`\n" +
                "Error: `%s`",
                context, truncate(errorMessage, 500)
        );
        sendMessage(message);
    }

    private String resolveExitEmoji(String exitReason, double pnl) {
        return switch (exitReason) {
            case "STOP_LOSS" -> "🔴";
            case "TAKE_PROFIT" -> "🟢";
            default -> pnl >= 0 ? "✅" : "❌";
        };
    }

    private void sendMessage(String text) {
        try {
            telegramApi.post()
                    .uri("/sendMessage")
                    .body(Map.of(
                            "chat_id", chatId,
                            "text", text,
                            "parse_mode", "Markdown"
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Telegram notification sent successfully.");
        } catch (Exception e) {
            // Notificações não devem derrubar o motor de trading
            log.error("Failed to send Telegram notification: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
