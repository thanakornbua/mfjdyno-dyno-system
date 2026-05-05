package com.dyno.view;

import com.dyno.presenter.OperatorViewModel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.GridLayout;

public final class SettingsPanel extends JPanel {
    private final JLabel statusNote = new JLabel("Settings layout is available for operator configuration.");

    public SettingsPanel() {
        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(OperatorUi.APP_BACKGROUND);

        JPanel content = new JPanel();
        content.setOpaque(true);
        content.setBackground(OperatorUi.APP_BACKGROUND);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(18, 24, 24, 24));

        statusNote.setForeground(OperatorUi.TEXT_MUTED);
        statusNote.setFont(OperatorUi.bodyFont(14f));
        content.add(statusNote);
        content.add(Box.createVerticalStrut(14));
        content.add(section(
            "Barometer / ISO J1349",
            field("ISO J1349 Correction", new JComboBox<String>(new String[] {"Enabled", "Disabled"})),
            field("Barometer Source", new JComboBox<String>(new String[] {"BME280", "BMP280", "Auto"}))
        ));
        content.add(Box.createVerticalStrut(14));
        content.add(section(
            "Encoder Config",
            field("Roller Sensor Mode", new JComboBox<String>(new String[] {"Rotary Encoder", "Proximity"})),
            field("Capture Source", new JComboBox<String>(new String[] {"Hardware Encoder", "Digital Input"}))
        ));
        content.add(Box.createVerticalStrut(14));
        content.add(section(
            "Engine RPM Config",
            field("Engine Stroke", new JComboBox<String>(new String[] {"2 Stroke", "4 Stroke"})),
            field("Cylinder Count", new JComboBox<String>(new String[] {"1", "2", "3", "4", "6", "8"}))
        ));
        content.add(Box.createVerticalStrut(14));
        content.add(section(
            "AFR / CAN Config",
            field("AFR Input", new JComboBox<String>(new String[] {"Analog AFR", "CAN AFR", "Disabled"})),
            field("CAN Engine RPM", new JComboBox<String>(new String[] {"Disabled", "Enabled"}))
        ));
        content.add(Box.createVerticalStrut(14));
        content.add(section(
            "Future Sensors",
            field("Expansion Slot A", new JComboBox<String>(new String[] {"Not Configured"})),
            field("Expansion Slot B", new JComboBox<String>(new String[] {"Not Configured"}))
        ));

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(OperatorUi.APP_BACKGROUND);
        add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel section(String title, JPanel... rows) {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setOpaque(true);
        card.setBackground(OperatorUi.SURFACE);
        card.setBorder(OperatorUi.layeredCardBorder(18, 18, 18, 18));

        JLabel heading = new JLabel(title);
        heading.setForeground(OperatorUi.TEXT_PRIMARY);
        heading.setFont(OperatorUi.titleFont(18f));
        card.add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridLayout(0, 1, 0, 10));
        body.setOpaque(false);
        for (JPanel row : rows) {
            body.add(row);
        }
        card.add(body, BorderLayout.CENTER);
        card.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 200));
        return card;
    }

    private JPanel field(String labelText, JComboBox<String> comboBox) {
        JPanel row = new JPanel(new BorderLayout(10, 6));
        row.setOpaque(false);

        JLabel label = new JLabel(labelText);
        label.setForeground(OperatorUi.TEXT_MUTED);
        label.setFont(OperatorUi.bodyFont(13f));
        row.add(label, BorderLayout.NORTH);

        comboBox.setBackground(OperatorUi.SURFACE_ALT);
        comboBox.setForeground(OperatorUi.TEXT_PRIMARY);
        comboBox.setBorder(OperatorUi.softInsetBorder());
        row.add(comboBox, BorderLayout.CENTER);
        return row;
    }

    public void render(OperatorViewModel model) {
        statusNote.setText(
            "Operator config layout only in this stage. Current state: "
                + model.getStateText()
                + " | Run label: "
                + model.getRunLabel()
        );
    }
}
