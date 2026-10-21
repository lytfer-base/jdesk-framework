package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.ButtonModel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Check box and radio button glyph in the theme colors. It is needed because, under the system visual style,
 * {@code WindowsIconFactory} lets the native theme paint the box, which stays light in dark themes whatever
 * {@code CheckBox.background} says.
 * Read colors in {@link #paintIcon}: the icon is read from UIDefaults once and shared, so cached colors would freeze.
 */
public final class JdfToggleIcon implements Icon {

    /** Unscaled glyph size; close to the native 13, and even so that the check mark aligns. */
    private static final int SIZE = 14;

    /** Unscaled corner radius of the check box; radio buttons are full circles. */
    private static final int ARC = 4;

    private final boolean round;

    private JdfToggleIcon(boolean round) {
        this.round = round;
    }

    public static JdfToggleIcon checkBox() {
        return new JdfToggleIcon(false);
    }

    public static JdfToggleIcon radioButton() {
        return new JdfToggleIcon(true);
    }

    @Override
    public int getIconWidth() {
        return JdfScale.scale(SIZE);
    }

    @Override
    public int getIconHeight() {
        return JdfScale.scale(SIZE);
    }

    @Override
    public void paintIcon(Component component, Graphics g, int x, int y) {
        ButtonModel model = component instanceof AbstractButton button ? button.getModel() : null;
        boolean selected = model != null && model.isSelected();
        boolean enabled = model == null ? component.isEnabled() : model.isEnabled();
        int size = getIconWidth();
        // A 1 px stroke on the half pixel, so antialiasing doesn't smear it into two gray pixels.
        float stroke = Math.max(1f, JdfScale.scale(1f));
        float inset = stroke / 2f;
        int arc = round ? size : JdfScale.scale(ARC);

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);

            g2.setColor(fill(selected, enabled));
            g2.fillRoundRect(0, 0, size, size, arc, arc);

            g2.setStroke(new BasicStroke(stroke));
            g2.setColor(outline(selected, enabled));
            g2.draw(new java.awt.geom.RoundRectangle2D.Float(
                    inset, inset, size - stroke, size - stroke, arc, arc));

            if (selected) {
                g2.setColor(mark(enabled));
                if (round) {
                    int dot = Math.round(size * 0.34f);
                    g2.fillOval((size - dot) / 2, (size - dot) / 2, dot, dot);
                }
                else {
                    g2.setStroke(new BasicStroke(Math.max(1.5f, JdfScale.scale(1.6f)),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D.Float check = new Path2D.Float();
                    check.moveTo(size * 0.26f, size * 0.52f);
                    check.lineTo(size * 0.44f, size * 0.70f);
                    check.lineTo(size * 0.76f, size * 0.31f);
                    g2.draw(check);
                }
            }
        }
        finally {
            g2.dispose();
        }
    }

    private static Color fill(boolean selected, boolean enabled) {
        if (!enabled) {
            return JdfColors.surfaceAlt();
        }
        return selected ? JdfColors.accent() : JdfColors.controlBackground();
    }

    /**
     * Box outline; unselected it uses {@code Jdf.weakText}, the weakest readable tone, since the hairline
     * {@code Jdf.border} would be almost invisible on a box.
     */
    private static Color outline(boolean selected, boolean enabled) {
        if (!enabled) {
            return JdfColors.disabledText();
        }
        return selected ? JdfColors.accent() : JdfColors.weakText();
    }

    private static Color mark(boolean enabled) {
        return enabled ? JdfColors.accentForeground() : JdfColors.disabledText();
    }
}
