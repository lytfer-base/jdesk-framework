package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;

import javax.swing.JSeparator;

/** Separator in the JDesk border color. */
public class JdfSeparator extends JSeparator implements JdfThemedComponent {

    public JdfSeparator(int orientation) {
        super(orientation);
        updateJdfTheme();
    }

    @Override
    public void updateJdfTheme() {
        setForeground(JdfColors.border());
        setBackground(JdfColors.border());
    }
}
