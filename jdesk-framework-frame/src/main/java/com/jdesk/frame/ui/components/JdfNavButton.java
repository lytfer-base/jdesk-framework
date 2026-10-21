package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Themed navigation button for top bars, sidebars and pills. Its look follows the Swing selection state and its own
 * hover and press states, drawn with JDesk semantic colors and scaling.
 */
public class JdfNavButton extends JButton implements JdfThemedComponent {

    /** Layout variant, which sets the size, padding and default alignment. */
    public enum Variant {
        TOP,
        SIDE,
        PILL
    }

    private final Variant variant;
    /** Mouse states kept by a listener, so the background updates without a custom UI delegate. */
    private boolean hovered;
    private boolean pressed;

    public JdfNavButton(String text, Variant variant) {
        super(text);
        this.variant = variant == null ? Variant.PILL : variant;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(true);
        setFont(JdfFonts.derive(getFont(), Font.BOLD, 13f));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                updateJdfTheme();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                pressed = false;
                updateJdfTheme();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                pressed = true;
                updateJdfTheme();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pressed = false;
                updateJdfTheme();
            }
        });
        installVariantDefaults();
        updateJdfTheme();
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        updateJdfTheme();
    }

    @Override
    public void updateJdfTheme() {
        Color background = backgroundForState();
        setBackground(background);
        setForeground(isSelected() ? JdfColors.selectionForeground() : JdfColors.text());
        setBorder(BorderFactory.createCompoundBorder(
                JdfUi.lineBorder(isSelected() ? JdfColors.focus() : background),
                JdfUi.empty(variant == Variant.TOP ? 0 : 8, 12, variant == Variant.TOP ? 0 : 8, 12)));
    }

    private void installVariantDefaults() {
        if (variant == Variant.TOP) {
            setPreferredSize(JdfUi.size(96, 36));
            setMargin(JdfUi.insets(0, 14, 0, 14));
            return;
        }
        setHorizontalAlignment(LEFT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, JdfScale.scale(38)));
        setMargin(JdfUi.insets(0, 14, 0, 14));
    }

    private Color backgroundForState() {
        if (!isEnabled()) {
            return JdfColors.surfaceAlt();
        }
        if (pressed) {
            return JdfColors.pressedBackground();
        }
        if (isSelected()) {
            return JdfColors.activeBackground();
        }
        if (hovered) {
            return JdfColors.hoverBackground();
        }
        return variant == Variant.TOP ? JdfColors.panelBackground() : JdfColors.surfaceAlt();
    }
}
