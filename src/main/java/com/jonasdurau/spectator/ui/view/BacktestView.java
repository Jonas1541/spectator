package com.jonasdurau.spectator.ui.view;

import com.jonasdurau.spectator.core.backtest.BacktestEngineService;
import com.jonasdurau.spectator.core.backtest.BacktestReport;
import com.jonasdurau.spectator.core.domain.Candle;
import com.jonasdurau.spectator.core.repository.CandleRepository;
import com.jonasdurau.spectator.core.service.HistoricalSyncService;
import com.jonasdurau.spectator.core.strategy.TradingStrategy;
import com.jonasdurau.spectator.ui.components.TradingViewChart;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Route("backtest")
@PageTitle("Spectator | Backtest Studio")
public class BacktestView extends VerticalLayout {

    // Helper Record para o menu suspenso
    public record StrategyOption(String name, List<TradingStrategy> strategies) {}

    private final HistoricalSyncService syncService;
    private final BacktestEngineService backtestEngine;
    private final CandleRepository candleRepository;
    private final List<TradingStrategy> availableStrategies;

    // UI Components
    private final DatePicker startDatePicker = new DatePicker("Start Date");
    private final DatePicker endDatePicker = new DatePicker("End Date");
    private final ComboBox<StrategyOption> strategySelector = new ComboBox<>("Strategy Mode"); // <-- Mudou o tipo aqui
    private final Button runButton = new Button("Sync & Run Backtest");
    
    // Results Board
    private final Span winRateLabel = new Span("-");
    private final Span pnlLabel = new Span("-");
    private final Span tradesLabel = new Span("-");
    private final Span drawdownLabel = new Span("-");
    
    private final TradingViewChart chart = new TradingViewChart();

    public BacktestView(HistoricalSyncService syncService, 
                        BacktestEngineService backtestEngine, 
                        CandleRepository candleRepository, 
                        List<TradingStrategy> availableStrategies) {
        this.syncService = syncService;
        this.backtestEngine = backtestEngine;
        this.candleRepository = candleRepository;
        this.availableStrategies = availableStrategies;

        setSizeFull();
        setPadding(true);

        createHeader();
        createControlPanel();
        createResultsBoard();
        
        add(chart);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        attachEvent.getUI().getPage().executeJs("setTimeout(() => document.documentElement.setAttribute('theme', 'dark'), 0);");
    }

    private void createHeader() {
        H1 title = new H1("Backtest Studio");
        title.addClassNames(LumoUtility.FontSize.XXLARGE, LumoUtility.Margin.Bottom.NONE);
        add(title);
    }

    private void createControlPanel() {
        HorizontalLayout controls = new HorizontalLayout();
        controls.setAlignItems(Alignment.BASELINE);
        controls.setWidthFull();

        startDatePicker.setValue(LocalDate.now().minusMonths(3));
        endDatePicker.setValue(LocalDate.now());
        
        // --- MONTANDO AS OPÇÕES DO MENU ---
        List<StrategyOption> options = new ArrayList<>();
        
        // 1. A opção principal do Master Engine (Leva todas as estratégias)
        options.add(new StrategyOption("Master Engine (Auto-Switch)", availableStrategies));
        
        // 2. As opções isoladas para debug
        for (TradingStrategy s : availableStrategies) {
            options.add(new StrategyOption(s.getName() + " (Isolated)", List.of(s)));
        }

        strategySelector.setItems(options);
        strategySelector.setItemLabelGenerator(StrategyOption::name);
        if (!options.isEmpty()) {
            strategySelector.setValue(options.get(0));
        }

        runButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        runButton.addClickListener(e -> executeBacktest());

        controls.add(strategySelector, startDatePicker, endDatePicker, runButton);
        add(controls);
    }

    private void createResultsBoard() {
        HorizontalLayout board = new HorizontalLayout();
        board.setWidthFull();
        board.setJustifyContentMode(JustifyContentMode.BETWEEN);
        board.addClassNames(LumoUtility.Background.BASE, LumoUtility.Padding.LARGE, LumoUtility.BorderRadius.LARGE);

        board.add(createMetric("Win Rate", winRateLabel));
        board.add(createMetric("Net Profit", pnlLabel));
        board.add(createMetric("Total Trades", tradesLabel));
        board.add(createMetric("Max Drawdown", drawdownLabel));

        add(board);
    }

    private VerticalLayout createMetric(String title, Span valueLabel) {
        valueLabel.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);
        VerticalLayout layout = new VerticalLayout(new Span(title), valueLabel);
        layout.setSpacing(false);
        layout.setPadding(false);
        layout.setAlignItems(Alignment.CENTER);
        return layout;
    }

    private void executeBacktest() {
        if (startDatePicker.getValue() == null || endDatePicker.getValue() == null || strategySelector.getValue() == null) {
            Notification.show("Please select all fields.", 3000, Notification.Position.TOP_CENTER);
            return;
        }

        runButton.setEnabled(false);
        runButton.setText("Downloading & Processing...");
        
        java.time.Instant start = startDatePicker.getValue().atStartOfDay().toInstant(ZoneOffset.UTC);
        java.time.Instant end = endDatePicker.getValue().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        StrategyOption selectedOption = strategySelector.getValue();

        UI ui = UI.getCurrent();
        Thread backgroundThread = new Thread(() -> {
            try {
                syncService.syncPeriod("BTCUSDT", "4h", start, end);
                syncService.syncPeriod("BTCUSDT", "1h", start, end);

                // Passa o nome da opção e a lista de estratégias para o motor!
                BacktestReport report = backtestEngine.runBacktest(
                        selectedOption.name(), 
                        selectedOption.strategies(), 
                        "BTCUSDT", 
                        start, 
                        end, 
                        10000.0
                );
                
                List<Candle> chartData = candleRepository.findBySymbolAndTimeframeAndTimeBetweenOrderByTimeAsc("BTCUSDT", "1h", start, end);

                ui.access(() -> {
                    updateResultsBoard(report);
                    chart.setBacktestData(chartData, report.tradeLog(), report.regimeChanges());
                    
                    runButton.setEnabled(true);
                    runButton.setText("Sync & Run Backtest");
                    Notification.show("Backtest completed successfully!", 3000, Notification.Position.TOP_END).addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                });

            } catch (Exception e) {
                ui.access(() -> {
                    runButton.setEnabled(true);
                    runButton.setText("Sync & Run Backtest");
                    Notification.show("Error: " + e.getMessage(), 5000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.LUMO_ERROR);
                });
            }
        });
        
        backgroundThread.start();
    }

    private void updateResultsBoard(BacktestReport report) {
        winRateLabel.setText(String.format("%.2f%%", report.winRate()));
        
        String pnlStr = String.format("$%.2f", report.netProfit());
        pnlLabel.setText(pnlStr);
        pnlLabel.removeClassNames(LumoUtility.TextColor.SUCCESS, LumoUtility.TextColor.ERROR);
        pnlLabel.addClassName(report.netProfit() >= 0 ? LumoUtility.TextColor.SUCCESS : LumoUtility.TextColor.ERROR);

        tradesLabel.setText(String.format("%d", report.totalTrades()));
        
        drawdownLabel.setText(String.format("%.2f%%", report.maxDrawdown()));
        drawdownLabel.addClassName(LumoUtility.TextColor.ERROR);
    }
}