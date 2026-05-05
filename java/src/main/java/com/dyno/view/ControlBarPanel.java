package com.dyno.view;

import com.dyno.presenter.OperatorViewModel;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.FlowLayout;

public final class ControlBarPanel extends JPanel {
    private final JButton startButton = new JButton("START");
    private final JButton stopButton = new JButton("STOP");
    private final JButton runModeButton = new JButton("RUN MODE");
    private final JButton printButton = new JButton("PRINT");
    private final java.awt.Color startColor = OperatorUi.SUCCESS;
    private final java.awt.Color stopColor = OperatorUi.ALERT;
    private final java.awt.Color neutralColor = OperatorUi.SURFACE_RAISED;

    public ControlBarPanel(ControlActionListener listener) {
        setOpaque(true);
        setBackground(OperatorUi.SURFACE);
        setBorder(OperatorUi.layeredCardBorder(14, 14, 14, 14));
        setLayout(new FlowLayout(FlowLayout.CENTER, 14, 0));

        configureButton(startButton, OperatorUi.SUCCESS, listener::onStartRequested);
        configureButton(stopButton, OperatorUi.ALERT, listener::onStopRequested);
        configureButton(runModeButton, OperatorUi.SURFACE_RAISED, listener::onRunModeRequested);
        configureButton(printButton, OperatorUi.SURFACE_RAISED, listener::onPrintRequested);

        add(startButton);
        add(stopButton);
        add(runModeButton);
        add(printButton);
    }

    private void configureButton(JButton button, java.awt.Color color, Runnable action) {
        java.awt.Color foreground = (OperatorUi.SUCCESS.equals(color) || OperatorUi.ALERT.equals(color))
            ? OperatorUi.BUTTON_TEXT_DARK
            : OperatorUi.TEXT_PRIMARY;
        OperatorUi.styleActionButton(button, color, foreground);
        button.addActionListener(event -> action.run());
    }

    public void render(OperatorViewModel model) {
        startButton.setEnabled(model.isStartEnabled());
        stopButton.setEnabled(model.isStopEnabled());
        runModeButton.setEnabled(model.isRunModeEnabled());
        printButton.setEnabled(model.isPrintEnabled());
        styleEnabledState(startButton, startColor, OperatorUi.BUTTON_TEXT_DARK);
        styleEnabledState(stopButton, stopColor, OperatorUi.BUTTON_TEXT_DARK);
        styleEnabledState(runModeButton, neutralColor, OperatorUi.TEXT_PRIMARY);
        styleEnabledState(printButton, neutralColor, OperatorUi.TEXT_PRIMARY);
    }

    private void styleEnabledState(JButton button, java.awt.Color enabledBackground, java.awt.Color enabledForeground) {
        if (button.isEnabled()) {
            button.setBackground(enabledBackground);
            button.setForeground(enabledForeground);
        } else {
            button.setBackground(OperatorUi.SURFACE_ALT);
            button.setForeground(OperatorUi.TEXT_SUBTLE);
        }
    }
}
