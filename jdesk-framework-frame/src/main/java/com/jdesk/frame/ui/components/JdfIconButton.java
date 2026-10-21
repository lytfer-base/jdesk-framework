package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Compact icon button for toolbars and application shells; a theme refresh sets its background, foreground and
 * border from the hover and focus states.
 */
public class JdfIconButton extends JButton implements JdfThemedComponent {

    private boolean hovered;

    public JdfIconButton(Icon icon, String tooltip) {
        super(icon);
        setToolTipText(tooltip);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(true);
        setPreferredSize(JdfUi.size(32, 32));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                updateJdfTheme();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                updateJdfTheme();
            }
        });
        updateJdfTheme();
    }

    @Override
    public void updateJdfTheme() {
        setBackground(hovered ? JdfColors.hoverBackground() : JdfColors.panelBackground());
        setForeground(JdfColors.text());
        setBorder(JdfUi.lineBorder(isFocusOwner() ? JdfColors.focus() : getBackground()));
    }
}
