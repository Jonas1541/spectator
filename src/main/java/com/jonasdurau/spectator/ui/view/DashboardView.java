package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.domain.MarketRegime;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.ui.broadcaster.MarketDataBroadcaster;
import com.jonasdurau.spectator.ui.broadcaster.MarketTick;
import com.jonasdurau.spectator.ui.components.TradingViewChart;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

@Route("")
@PageTitle("Spectator | Trading Terminal")
public class DashboardView extends VerticalLayout {

    private final MarketDataBroadcaster broadcaster;
    private final CandleRepository candleRepository;
    private Consumer<MarketTick> broadcasterListener;

    // Componentes Visuais
    private final H2 priceLabel = new H2("Loading...");
    private final Span regimeBadge = new Span("ANALYZING");
    private final TradingViewChart chart = new TradingViewChart();

    private final NumberFormat currencyFormatter = NumberFormat.getCurrencyInstance(Locale.US);

    public DashboardView(MarketDataBroadcaster broadcaster, CandleRepository candleRepository) {
        this.broadcaster = broadcaster;
        this.candleRepository = candleRepository;

        setSizeFull();
        setPadding(true);
        setSpacing(true);

        createHeader();
        createMetricsBoard();

        // Adicionamos o gráfico no lugar do grid
        add(chart);

        loadInitialData();
    }

    private void createHeader() {
        H1 title = new H1("Spectator Engine");
        title.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.Margin.Bottom.NONE);
        add(title);
    }

    private void createMetricsBoard() {
        HorizontalLayout board = new HorizontalLayout();
        board.setWidthFull();
        board.setAlignItems(Alignment.CENTER);
        board.setJustifyContentMode(JustifyContentMode.BETWEEN);
        // Cores ajustadas para combinar com o fundo escuro
        board.addClassNames(LumoUtility.Background.BASE, LumoUtility.Padding.LARGE, LumoUtility.BorderRadius.LARGE);

        VerticalLayout priceLayout = new VerticalLayout(new Span("BTC/USDT Live Price"), priceLabel);
        priceLayout.setSpacing(false);
        priceLayout.setPadding(false);
        priceLabel.addClassNames(LumoUtility.TextColor.PRIMARY, LumoUtility.Margin.NONE);

        VerticalLayout regimeLayout = new VerticalLayout(new Span("Market Regime"), regimeBadge);
        regimeLayout.setSpacing(false);
        regimeLayout.setPadding(false);
        regimeLayout.setAlignItems(Alignment.END);
        regimeBadge.addClassNames(LumoUtility.Padding.Horizontal.MEDIUM, LumoUtility.Padding.Vertical.SMALL,
                LumoUtility.BorderRadius.LARGE, LumoUtility.FontWeight.BOLD);

        board.add(priceLayout, regimeLayout);
        add(board);
    }

    private void loadInitialData() {
        // Puxa os últimos 500 candles para o gráfico ficar bonito
        List<Candle> initialCandles = candleRepository.findLastCandles("BTCUSDT", 500);

        if (!initialCandles.isEmpty()) {
            // Reverte a ordem porque o TradingView exige do mais velho para o mais novo
            Collections.reverse(initialCandles);

            chart.setHistoricalData(initialCandles);

            // Pega o candle mais recente para o painel superior
            Candle last = initialCandles.get(initialCandles.size() - 1);
            updateMetrics(last, MarketRegime.SIDEWAYS);
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();

        // A forma oficial no Vaadin 25 de forçar o Lumo Dark globalmente na tag <html>
        ui.getPage().executeJs("document.documentElement.setAttribute('theme', 'dark');");

        broadcasterListener = tick -> ui.access(() -> {
            updateMetrics(tick.candle(), tick.regime());
            // Atualiza o gráfico de forma segura e não bloqueante
            chart.updateLiveTick(tick.candle());
        });

        broadcaster.register(broadcasterListener);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        broadcaster.unregister(broadcasterListener);
    }

    private void updateMetrics(Candle candle, MarketRegime regime) {
        priceLabel.setText(currencyFormatter.format(candle.getClose()));
        regimeBadge.setText(regime.name().replace("_", " "));

        regimeBadge.removeClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS,
                LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR,
                LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING,
                LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);

        switch (regime) {
            case TRENDING_UP ->
                regimeBadge.addClassNames(LumoUtility.Background.SUCCESS_10, LumoUtility.TextColor.SUCCESS);
            case TRENDING_DOWN ->
                regimeBadge.addClassNames(LumoUtility.Background.ERROR_10, LumoUtility.TextColor.ERROR);
            case VOLATILE ->
                regimeBadge.addClassNames(LumoUtility.Background.WARNING_10, LumoUtility.TextColor.WARNING);
            default -> regimeBadge.addClassNames(LumoUtility.Background.CONTRAST_10, LumoUtility.TextColor.BODY);
        }
    }
}