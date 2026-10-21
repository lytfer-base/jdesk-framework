package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.scale.JdfFonts;

import javax.swing.JLabel;

/** Small, muted label for status bars, metadata lines and secondary shell text. */
public class JdfStatusLabel extends JLabel implements JdfThemedComponent {

    public JdfStatusLabel(String text) {
        super(text);
        setFont(JdfFonts.small());
        updateJdfTheme();
    }

    @Override
    public void updateJdfTheme() {
        setForeground(JdfColors.mutedText());
        setBackground(JdfColors.panelBackground());
    }
}
