package com.dyno.view;

import com.dyno.presenter.RunIdentityState;
import com.dyno.presenter.TelemetryPresenter;
import com.dyno.state.LiveTelemetryState;
import com.dyno.ws.DynoWebSocketClient;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;

public final class OperatorConsoleFrame extends JFrame {
    private static final String SCREEN_LIVE = "live";
    private static final String SCREEN_SETTINGS = "settings";

    private final DynoWebSocketClient webSocketClient;
    private final TelemetryPresenter telemetryPresenter;
    private final HeaderBarPanel headerBarPanel;
    private final LiveRunPanel liveRunPanel;
    private final SettingsPanel settingsPanel;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);
    private String currentScreen = SCREEN_LIVE;

    public OperatorConsoleFrame(LiveTelemetryState state, DynoWebSocketClient webSocketClient) {
        super("Dyno Operator Console");
        this.webSocketClient = webSocketClient;
        this.telemetryPresenter = new TelemetryPresenter(state);
        this.headerBarPanel = new HeaderBarPanel(new HeaderBarPanel.NavigationListener() {
            @Override
            public void onShowLiveRun() {
                showScreen(SCREEN_LIVE);
            }

            @Override
            public void onShowSettings() {
                showScreen(SCREEN_SETTINGS);
            }
        });
        this.liveRunPanel = new LiveRunPanel(new ControlActionListener() {
            @Override
            public void onStartRequested() {
                handleStart();
            }

            @Override
            public void onStopRequested() {
                webSocketClient.sendCommand("stop");
            }

            @Override
            public void onRunModeRequested() {
                openRunSetupDialog();
            }

            @Override
            public void onPrintRequested() {
                JOptionPane.showMessageDialog(
                    OperatorConsoleFrame.this,
                    "Print is not implemented in this stage.",
                    "PRINT",
                    JOptionPane.INFORMATION_MESSAGE
                );
            }
        });
        this.settingsPanel = new SettingsPanel();

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1560, 920));
        getContentPane().setBackground(OperatorUi.APP_BACKGROUND);
        setLayout(new BorderLayout());

        cardPanel.add(liveRunPanel, SCREEN_LIVE);
        cardPanel.add(settingsPanel, SCREEN_SETTINGS);

        add(headerBarPanel, BorderLayout.NORTH);
        add(cardPanel, BorderLayout.CENTER);

        telemetryPresenter.addListener(event -> render());
        render();

        pack();
        setLocationByPlatform(true);
    }

    private void render() {
        com.dyno.presenter.OperatorViewModel model = telemetryPresenter.getViewModel();
        headerBarPanel.render(model, currentScreen);
        liveRunPanel.render(model);
        settingsPanel.render(model);
    }

    private void showScreen(String screenName) {
        currentScreen = screenName;
        cardLayout.show(cardPanel, screenName);
        render();
    }

    private void handleStart() {
        if (!telemetryPresenter.hasPreparedRun()) {
            if (!openRunSetupDialog()) {
                return;
            }
        }

        RunIdentityState.PreparedRun preparedRun = telemetryPresenter.getPreparedRun();
        boolean sent = preparedRun != null && webSocketClient.sendRunCommand(preparedRun.getPlate());
        if (!sent) {
            JOptionPane.showMessageDialog(
                this,
                "Unable to send START command. Check the dyno connection.",
                "START",
                JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        telemetryPresenter.commitPreparedRun();
    }

    private boolean openRunSetupDialog() {
        String initialPlate = telemetryPresenter.getLastUsedPlate();
        RunSetupDialog dialog = new RunSetupDialog(this, telemetryPresenter, initialPlate);
        String selectedPlate = dialog.showDialog();
        if (selectedPlate == null) {
            return false;
        }
        telemetryPresenter.prepareRun(selectedPlate);
        return true;
    }
}
