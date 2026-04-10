package com.will.cellseg;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** Shared Swing form helpers for plugin dialogs. */
public final class DialogFormUtils {
    private DialogFormUtils() {}

    public static JPanel createFormPanel() {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return panel;
    }

    public static void addRow(JPanel panel, int row, Component label, Component field) {
        if (label instanceof JLabel) {
            ((JLabel) label).setHorizontalAlignment(JLabel.RIGHT);
        }
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.insets = new Insets(0, 0, 8, 10);
        panel.add(label, gbc);

        final GridBagConstraints fieldGbc = new GridBagConstraints();
        fieldGbc.gridx = 1;
        fieldGbc.gridy = row;
        fieldGbc.weightx = 1.0;
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        fieldGbc.insets = new Insets(0, 0, 8, 0);
        panel.add(field, fieldGbc);
    }

    public static void addCheckRow(JPanel panel, int row, JCheckBox component) {
        component.setHorizontalTextPosition(SwingConstants.RIGHT);
        component.setHorizontalAlignment(JCheckBox.LEFT);
        component.setBorder(BorderFactory.createEmptyBorder());

        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 8, 0);
        panel.add(component, gbc);
    }

    public static void addVerticalGlue(JPanel panel, int row) {
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(new JPanel(), gbc);
    }
}
