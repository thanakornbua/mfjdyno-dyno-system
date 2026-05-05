package com.dyno.view;

import com.dyno.presenter.RunIdentityState;
import com.dyno.presenter.TelemetryPresenter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

public final class RunSetupDialog extends JDialog {
    private final TelemetryPresenter presenter;
    private final JTextField plateField = new JTextField();
    private final JLabel previewLabel = new JLabel("NO-PLATE-01", SwingConstants.CENTER);
    private String selectedPlate;

    public RunSetupDialog(JFrame owner, TelemetryPresenter presenter, String initialPlate) {
        super(owner, "Run Setup", true);
        this.presenter = presenter;
        setLayout(new BorderLayout(0, 18));
        getContentPane().setBackground(OperatorUi.SURFACE);
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel heading = new JLabel("License Plate");
        heading.setForeground(OperatorUi.TEXT_PRIMARY);
        heading.setFont(OperatorUi.titleFont(20f));

        JLabel helper = new JLabel("Preview run identity before START");
        helper.setForeground(OperatorUi.TEXT_MUTED);
        helper.setFont(OperatorUi.bodyFont(13f));

        plateField.setText(initialPlate == null ? "" : initialPlate);
        plateField.setFont(OperatorUi.monoFont(18f, java.awt.Font.PLAIN));
        plateField.setBackground(OperatorUi.SURFACE_ALT);
        plateField.setForeground(OperatorUi.TEXT_PRIMARY);
        plateField.setCaretColor(OperatorUi.ACCENT);
        plateField.setBorder(OperatorUi.layeredCardBorder(12, 12, 12, 12));

        previewLabel.setOpaque(true);
        previewLabel.setBackground(OperatorUi.SURFACE_RAISED);
        previewLabel.setForeground(OperatorUi.ACCENT);
        previewLabel.setFont(OperatorUi.monoFont(28f, java.awt.Font.BOLD));
        previewLabel.setBorder(OperatorUi.layeredCardBorder(14, 14, 14, 14));

        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.add(heading, BorderLayout.NORTH);

        JPanel middle = new JPanel(new BorderLayout(0, 8));
        middle.setOpaque(false);
        middle.add(helper, BorderLayout.NORTH);
        middle.add(plateField, BorderLayout.CENTER);
        middle.add(previewLabel, BorderLayout.SOUTH);
        center.add(middle, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        JButton applyButton = new JButton("Apply");
        OperatorUi.styleInputButton(cancelButton);
        OperatorUi.styleActionButton(applyButton, OperatorUi.ACCENT, OperatorUi.BUTTON_TEXT_DARK);
        cancelButton.addActionListener(event -> {
            selectedPlate = null;
            dispose();
        });
        applyButton.addActionListener(event -> {
            selectedPlate = plateField.getText().trim();
            dispose();
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(applyButton);

        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        plateField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updatePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updatePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updatePreview();
            }
        });

        updatePreview();
        pack();
        setSize(420, getHeight());
        setLocationRelativeTo(owner);
    }

    public String showDialog() {
        setVisible(true);
        return selectedPlate;
    }

    private void updatePreview() {
        RunIdentityState.PreparedRun preview = presenter.previewRun(plateField.getText());
        previewLabel.setText(preview.getRunLabel());
    }
}
