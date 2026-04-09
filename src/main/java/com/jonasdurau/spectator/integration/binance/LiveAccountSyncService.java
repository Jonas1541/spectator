package com.jonasdurau.spectator.integration.binance;

import com.jonasdurau.spectator.core.domain.Position;
import com.jonasdurau.spectator.core.domain.PositionStatus;
import com.jonasdurau.spectator.core.repository.PositionRepository;
import com.jonasdurau.spectator.core.service.BinanceRiskSyncService;
import com.jonasdurau.spectator.core.service.NotificationService;
import com.jonasdurau.spectator.integration.binance.dto.BinanceOpenOrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serviço de reconciliação que sincroniza o estado local com a Binance ao iniciar o bot.
 * Valida que as ordens SL/TP registradas no DB local ainda existem na exchange.
 * Se uma ordem foi executada pela Binance enquanto o bot estava offline,
 * fecha a posição localmente para evitar estado inconsistente.
 * Ativado apenas quando spectator.trading.live-enabled=true.
 */
@Service
@ConditionalOnProperty(name = "spectator.trading.live-enabled", havingValue = "true")
public class LiveAccountSyncService {

    private static final Logger log = LoggerFactory.getLogger(LiveAccountSyncService.class);

    private final PositionRepository positionRepository;
    private final RestClient authenticatedApi;
    private final BinanceApiSigner signer;
    private final BinanceRiskSyncService binanceRiskSyncService;
    private final NotificationService notificationService;

    public LiveAccountSyncService(PositionRepository positionRepository,
                                   @Qualifier("binanceAuthenticatedApi") RestClient authenticatedApi,
                                   BinanceApiSigner signer,
                                   BinanceRiskSyncService binanceRiskSyncService,
                                   NotificationService notificationService) {
        this.positionRepository = positionRepository;
        this.authenticatedApi = authenticatedApi;
        this.signer = signer;
        this.binanceRiskSyncService = binanceRiskSyncService;
        this.notificationService = notificationService;
    }

    @PostConstruct
    public void reconcileOnStartup() {
        log.info("🔄 Starting Binance Account Reconciliation...");

        List<Position> localOpenPositions = positionRepository.findByStatus(PositionStatus.OPEN);

        if (localOpenPositions.isEmpty()) {
            log.info("✅ No local open positions found. Reconciliation complete.");
            return;
        }

        for (Position position : localOpenPositions) {
            try {
                reconcilePosition(position);
            } catch (Exception e) {
                log.error("🚨 Failed to reconcile position {} for {}: {}",
                        position.getId(), position.getSymbol(), e.getMessage());
                notificationService.notifyCriticalError("Reconciliation",
                        String.format("Failed to reconcile %s position for %s: %s",
                                position.getSide(), position.getSymbol(), e.getMessage()));
            }
        }

        log.info("🔄 Binance Account Reconciliation complete. Processed {} positions.", localOpenPositions.size());
    }

    private void reconcilePosition(Position position) {
        String symbol = position.getSymbol();

        // Busca ordens abertas na Binance para este símbolo
        Set<Long> activeOrderIds = fetchOpenOrderIds(symbol);

        boolean slMissing = position.getBinanceSlOrderId() != null
                && !activeOrderIds.contains(position.getBinanceSlOrderId());
        boolean tpMissing = position.getBinanceTpOrderId() != null
                && !activeOrderIds.contains(position.getBinanceTpOrderId());

        if (slMissing && tpMissing) {
            // Ambas as ordens sumiram — posição provavelmente foi fechada pela Binance
            log.warn("🚨 Both SL ({}) and TP ({}) orders missing for {} {}. Position likely closed by Binance.",
                    position.getBinanceSlOrderId(), position.getBinanceTpOrderId(),
                    position.getSide(), symbol);

            position.setStatus(PositionStatus.CLOSED);
            position.setBinanceSlOrderId(null);
            position.setBinanceTpOrderId(null);
            positionRepository.save(position);

            notificationService.notifyCriticalError("Reconciliation",
                    String.format("%s %s position was closed by Binance while bot was offline. Synced locally.",
                            position.getSide(), symbol));
            return;
        }

        if (slMissing) {
            // SL foi executado pela Binance — a posição foi fechada pelo Stop Loss
            log.warn("⚠️ SL order {} missing for {} {}. Binance likely executed it. Closing position locally.",
                    position.getBinanceSlOrderId(), position.getSide(), symbol);

            // Cancela a ordem de TP que ainda está ativa
            binanceRiskSyncService.cancelTakeProfit(position);

            position.setStatus(PositionStatus.CLOSED);
            position.setBinanceSlOrderId(null);
            positionRepository.save(position);

            notificationService.notifyCriticalError("Reconciliation",
                    String.format("SL executed by Binance for %s %s while bot was offline. TP cancelled. Position synced.",
                            position.getSide(), symbol));
            return;
        }

        if (tpMissing) {
            // TP foi executado pela Binance — a posição foi fechada pelo Take Profit
            log.warn("⚠️ TP order {} missing for {} {}. Binance likely executed it. Closing position locally.",
                    position.getBinanceTpOrderId(), position.getSide(), symbol);

            // Cancela a ordem de SL que ainda está ativa
            binanceRiskSyncService.cancelStopLoss(position);

            position.setStatus(PositionStatus.CLOSED);
            position.setBinanceTpOrderId(null);
            positionRepository.save(position);

            notificationService.notifyCriticalError("Reconciliation",
                    String.format("TP executed by Binance for %s %s while bot was offline. SL cancelled. Position synced.",
                            position.getSide(), symbol));
            return;
        }

        // Tudo sincrono — ambas as ordens ainda estão ativas
        log.info("✅ Position {} {} is in sync. SL={} TP={} both active on Binance.",
                position.getSide(), symbol, position.getBinanceSlOrderId(), position.getBinanceTpOrderId());
    }

    private Set<Long> fetchOpenOrderIds(String symbol) {
        long timestamp = Instant.now().toEpochMilli();
        String queryString = "symbol=" + symbol + "&timestamp=" + timestamp;
        String signature = signer.sign(queryString);

        List<BinanceOpenOrderResponse> openOrders = authenticatedApi.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/fapi/v1/openOrders")
                        .queryParam("symbol", symbol)
                        .queryParam("timestamp", timestamp)
                        .queryParam("signature", signature)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (openOrders == null) {
            return Set.of();
        }

        return openOrders.stream()
                .map(BinanceOpenOrderResponse::orderId)
                .collect(Collectors.toSet());
    }
}
