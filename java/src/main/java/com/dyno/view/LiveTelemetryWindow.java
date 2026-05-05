package com.dyno.view;

import com.dyno.model.FrameMessage;
import com.dyno.state.ConnectionPhase;
import com.dyno.state.LiveTelemetrySnapshot;
import com.dyno.state.LiveTelemetryState;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LiveTelemetryWindow extends JFrame {
    private static final DecimalFormat ONE_DECIMAL = new DecimalFormat("0.0");

    private final JLabel statusBanner = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusDetail = new JLabel("", SwingConstants.CENTER);
    private final Map<String, JLabel> primaryValueLabels = new LinkedHashMap<String, JLabel>();
    private final Map<String, JLabel> secondaryValueLabels = new LinkedHashMap<String, JLabel>();
    private final DynoChartPanel chartPanel = new DynoChartPanel();

    public LiveTelemetryWindow(LiveTelemetryState state) {
        super("Dyno Live Telemetry");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1160, 760));
        setLayout(new BorderLayout(16, 16));

        JPanel statusPanel = new JPanel(new BorderLayout(0, 4));
        statusBanner.setOpaque(true);
        statusBanner.setFont(statusBanner.getFont().deriveFont(Font.BOLD, 18f));
        statusBanner.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        statusDetail.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        statusPanel.add(statusBanner, BorderLayout.CENTER);
        statusPanel.add(statusDetail, BorderLayout.SOUTH);

        JPanel primaryPanel = new JPanel();
        primaryPanel.setLayout(new BoxLayout(primaryPanel, BoxLayout.Y_AXIS));
        primaryPanel.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 10));
        primaryPanel.setPreferredSize(new Dimension(250, 0));
        addPrimaryMetric(primaryPanel, "State");
        addPrimaryMetric(primaryPanel, "Power (HP)");
        addPrimaryMetric(primaryPanel, "Torque (Nm)");
        addPrimaryMetric(primaryPanel, "Engine RPM");
        addPrimaryMetric(primaryPanel, "AFR");

        JPanel secondaryPanel = new JPanel(new GridLayout(1, 5, 14, 10));
        secondaryPanel.setBorder(BorderFactory.createEmptyBorder(8, 16, 16, 16));
        addSecondaryMetric(secondaryPanel, "Roller RPM");
        addSecondaryMetric(secondaryPanel, "Speed (km/h)");
        addSecondaryMetric(secondaryPanel, "Temp (C)");
        addSecondaryMetric(secondaryPanel, "Pressure (hPa)");
        addSecondaryMetric(secondaryPanel, "Fault Count");

        JPanel contentPanel = new JPanel(new BorderLayout(16, 12));
        contentPanel.add(chartPanel, BorderLayout.CENTER);
        contentPanel.add(primaryPanel, BorderLayout.EAST);

        add(statusPanel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        add(secondaryPanel, BorderLayout.SOUTH);

        state.addListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                LiveTelemetrySnapshot snapshot = (LiveTelemetrySnapshot) event.getNewValue();
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        render(snapshot);
                    }
                });
            }
        });

        render(state.getSnapshot());
        pack();
        setLocationByPlatform(true);
    }

    private void addPrimaryMetric(JPanel panel, String name) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xDADADA)),
            BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ));

        JLabel label = new JLabel(name);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));

        JLabel value = new JLabel("—");
        value.setFont(value.getFont().deriveFont(Font.BOLD, 24f));

        card.add(label, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        panel.add(card);
        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        primaryValueLabels.put(name, value);
    }

    private void addSecondaryMetric(JPanel panel, String name) {
        JPanel metric = new JPanel(new BorderLayout(0, 4));
        metric.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0xE3E3E3)),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        JLabel label = new JLabel(name, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));

        JLabel value = new JLabel("—", SwingConstants.CENTER);
        value.setFont(value.getFont().deriveFont(Font.PLAIN, 16f));

        metric.add(label, BorderLayout.NORTH);
        metric.add(value, BorderLayout.CENTER);
        panel.add(metric);
        secondaryValueLabels.put(name, value);
    }

    private void render(LiveTelemetrySnapshot snapshot) {
        updateStatus(snapshot.getConnectionPhase(), snapshot.getConnectionMessage());
        chartPanel.setRecording(snapshot.isRecording());
        chartPanel.setRunPoints(snapshot.getRunPoints());

        FrameMessage frame = snapshot.getFrame();
        setPrimaryValue("State", frame != null ? textOrDash(frame.getState()) : "—");
        setPrimaryValue("Power (HP)", formatDouble(frame != null ? frame.getPowerHp() : null));
        setPrimaryValue("Torque (Nm)", formatDouble(frame != null ? frame.getTorqueNm() : null));
        setPrimaryValue("Engine RPM", formatDouble(frame != null ? frame.getEngineRpm() : null));
        setPrimaryValue("AFR", formatDouble(frame != null ? frame.getAfr() : null));

        setSecondaryValue("Roller RPM", formatDouble(frame != null ? frame.getRpm() : null));
        setSecondaryValue("Speed (km/h)", formatDouble(frame != null ? frame.getSpeedKmh() : null));
        setSecondaryValue("Temp (C)", formatDouble(frame != null ? frame.getTemp() : null));
        setSecondaryValue("Pressure (hPa)", formatDouble(frame != null ? frame.getPressureHpa() : null));
        setSecondaryValue("Fault Count", formatInteger(frame != null ? frame.getFaultCount() : null));
    }

    private void updateStatus(ConnectionPhase phase, String message) {
        if (phase == ConnectionPhase.CONNECTED) {
            statusBanner.setBackground(new Color(0xD9F5D9));
            statusBanner.setForeground(new Color(0x124E12));
            statusBanner.setText("Connected");
        } else if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.AUTHENTICATING) {
            statusBanner.setBackground(new Color(0xFFF2CC));
            statusBanner.setForeground(new Color(0x6B5600));
            statusBanner.setText(phase == ConnectionPhase.CONNECTING ? "Connecting" : "Authenticating");
        } else if (phase == ConnectionPhase.RECONNECT_WAIT) {
            statusBanner.setBackground(new Color(0xFFE7BF));
            statusBanner.setForeground(new Color(0x7A4E00));
            statusBanner.setText("Disconnected: reconnecting");
        } else {
            statusBanner.setBackground(new Color(0xF8D7DA));
            statusBanner.setForeground(new Color(0x7A1420));
            statusBanner.setText("Disconnected");
        }

        statusDetail.setText(textOrDash(message));
    }

    private void setPrimaryValue(String key, String value) {
        primaryValueLabels.get(key).setText(value);
    }

    private void setSecondaryValue(String key, String value) {
        secondaryValueLabels.get(key).setText(value);
    }

    private String formatDouble(Double value) {
        if (value == null) {
            return "—";
        }
        return ONE_DECIMAL.format(value);
    }

    private String formatInteger(Integer value) {
        if (value == null) {
            return "—";
        }
        return Integer.toString(value.intValue());
    }

    private String textOrDash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "—";
        }
        return value;
    }
}
