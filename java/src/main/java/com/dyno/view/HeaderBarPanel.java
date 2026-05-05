package com.dyno.view;

import com.dyno.presenter.OperatorViewModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

public final class HeaderBarPanel extends JPanel {
    public interface NavigationListener {
        void onShowLiveRun();

        void onShowSettings();
    }

    private final JLabel connectionBadge = new JLabel("DISCONNECTED", SwingConstants.CENTER);
    private final JLabel stateBadge = new JLabel("IDLE", SwingConstants.CENTER);
    private final JLabel runLabel = new JLabel("NO RUN");
    private final JLabel plateLabel = new JLabel("PLATE —");
    private final JLabel bannerTitle = new JLabel("DISCONNECTED");
    private final JLabel bannerMessage = new JLabel("Disconnected from dyno backend");
    private final JPanel bannerPanel = new JPanel(new BorderLayout(12, 0));
    private final JLabel screenTitle = new JLabel("Live operator telemetry");
    private final JButton liveRunButton = new JButton("LIVE RUN");
    private final JButton settingsButton = new JButton("SETTINGS");

    public HeaderBarPanel(NavigationListener listener) {
        setLayout(new BorderLayout(0, 14));
        setOpaque(true);
        setBackground(OperatorUi.APP_BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(22, 24, 12, 24));

        JPanel topRow = new JPanel(new BorderLayout(16, 0));
        topRow.setOpaque(false);

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

        JLabel brand = new JLabel("DYNO OPERATOR CONSOLE");
        brand.setForeground(OperatorUi.TEXT_PRIMARY);
        brand.setFont(OperatorUi.titleFont(33f));

        runLabel.setForeground(OperatorUi.TEXT_PRIMARY);
        runLabel.setFont(OperatorUi.monoFont(28f, Font.BOLD));

        plateLabel.setForeground(OperatorUi.TEXT_SUBTLE);
        plateLabel.setFont(OperatorUi.bodyFont(16f));

        screenTitle.setForeground(OperatorUi.TEXT_MUTED);
        screenTitle.setFont(OperatorUi.bodyFont(17f));

        left.add(brand);
        left.add(Box.createVerticalStrut(4));
        left.add(screenTitle);
        left.add(Box.createVerticalStrut(10));
        left.add(runLabel);
        left.add(Box.createVerticalStrut(4));
        left.add(plateLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);

        styleBadge(connectionBadge);
        styleBadge(stateBadge);

        OperatorUi.styleNavButton(liveRunButton);
        OperatorUi.styleNavButton(settingsButton);
        liveRunButton.addActionListener(event -> listener.onShowLiveRun());
        settingsButton.addActionListener(event -> listener.onShowSettings());

        right.add(connectionBadge);
        right.add(stateBadge);
        right.add(liveRunButton);
        right.add(settingsButton);

        topRow.add(left, BorderLayout.WEST);
        topRow.add(right, BorderLayout.EAST);

        bannerPanel.setOpaque(true);
        bannerPanel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        bannerTitle.setFont(OperatorUi.titleFont(17f));
        bannerMessage.setFont(OperatorUi.bodyFont(16f));

        bannerPanel.add(bannerTitle, BorderLayout.WEST);
        bannerPanel.add(bannerMessage, BorderLayout.CENTER);

        add(topRow, BorderLayout.NORTH);
        add(bannerPanel, BorderLayout.SOUTH);
    }

    private void styleBadge(JLabel label) {
        label.setOpaque(true);
        label.setBorder(OperatorUi.softInsetBorder());
        label.setFont(OperatorUi.titleFont(15f));
    }

    public void render(OperatorViewModel model, String screenName) {
        runLabel.setText(model.getRunLabel());
        plateLabel.setText("PLATE " + model.getPlateText());
        screenTitle.setText("settings".equals(screenName) ? "Sensor and operator configuration" : "Live operator telemetry");

        connectionBadge.setText(model.getConnectionText());
        stateBadge.setText(model.getStateText());
        paintBadge(connectionBadge, "CONNECTED".equals(model.getConnectionText())
            ? OperatorViewModel.Tone.NORMAL
            : "RECONNECTING".equals(model.getConnectionText())
                ? OperatorViewModel.Tone.CAUTION
                : OperatorViewModel.Tone.ALERT);
        paintBadge(stateBadge, "FAULT".equals(model.getStateText())
            ? OperatorViewModel.Tone.FAULT
            : "RECORDING".equals(model.getStateText())
                ? OperatorViewModel.Tone.ACCENT
                : OperatorViewModel.Tone.NORMAL);

        bannerTitle.setText(model.getBanner().getTitle());
        bannerMessage.setText(model.getBanner().getMessage());
        bannerTitle.setForeground(OperatorUi.toneColor(model.getBanner().getTone()));
        bannerMessage.setForeground(OperatorUi.TEXT_PRIMARY);
        bannerPanel.setBackground(OperatorUi.toneBackground(model.getBanner().getTone()));
        bannerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(OperatorUi.toneBorder(model.getBanner().getTone()), 1, true),
            BorderFactory.createEmptyBorder(14, 16, 14, 16)
        ));

        boolean liveSelected = "live".equals(screenName);
        styleNav(liveRunButton, liveSelected);
        styleNav(settingsButton, !liveSelected);
    }

    private void paintBadge(JLabel label, OperatorViewModel.Tone tone) {
        Color color = OperatorUi.toneColor(tone);
        label.setBackground(OperatorUi.toneBackground(tone));
        label.setForeground(color);
    }

    private void styleNav(JButton button, boolean selected) {
        button.setBackground(selected ? OperatorUi.SURFACE_RAISED : OperatorUi.SURFACE_ALT);
        button.setForeground(selected ? OperatorUi.TEXT_PRIMARY : OperatorUi.TEXT_MUTED);
        button.setBorder(selected
            ? BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(OperatorUi.ACCENT, 1, true),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)
            )
            : BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(OperatorUi.BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(10, 18, 10, 18)
            ));
    }
}
