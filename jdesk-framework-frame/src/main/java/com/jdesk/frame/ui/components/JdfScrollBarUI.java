package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Flat scroll bar with no arrow buttons or border, just a rounded thumb in the theme colors; it matches the thin
 * scroll bars of web pages through the same {@code scrollbarThumb} token. It paints itself because, under the system
 * visual style, {@code WindowsScrollBarUI} lets the native theme paint and ignores the {@code ScrollBar.*} keys.
 * Read colors at paint time: cached ones would stay on the theme the window was created with.
 */
public final class JdfScrollBarUI extends BasicScrollBarUI {

    /** Unscaled scroll bar width; narrower than the native 17, like the web side. */
    private static final int WIDTH = 12;

    /** Unscaled gap on each side of the thumb, so that it floats on the track. */
    private static final int THUMB_INSET = 3;

    /** Called reflectively by UIManager when {@code ScrollBarUI} names this class. */
    public static ComponentUI createUI(JComponent component) {
        return new JdfScrollBarUI();
    }

    @Override
    protected void configureScrollBarColors() {
        // Don't read colors here: this runs once at install, so they would miss theme switches.
    }

    @Override
    public Dimension getPreferredSize(JComponent component) {
        int width = JdfScale.scale(WIDTH);
        return scrollbar.getOrientation() == javax.swing.JScrollBar.VERTICAL
                ? new Dimension(width, width * 4)
                : new Dimension(width * 4, width);
    }

    /** No arrow buttons: returns zero-size buttons, not null, since the base layout and hit tests expect them. */
    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zeroSizeButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zeroSizeButton();
    }

    @Override
    protected void paintTrack(Graphics g, JComponent component, Rectangle bounds) {
        // The track uses the window background, the same token as ScrollPane.background.
        g.setColor(JdfColors.background());
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    protected void paintThumb(Graphics g, JComponent component, Rectangle bounds) {
        if (bounds.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }
        int inset = JdfScale.scale(THUMB_INSET);
        int x = bounds.x + inset;
        int y = bounds.y + inset;
        int width = Math.max(1, bounds.width - inset * 2);
        int height = Math.max(1, bounds.height - inset * 2);
        int arc = Math.min(width, height);

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor());
            g2.fillRoundRect(x, y, width, height, arc, arc);
        }
        finally {
            g2.dispose();
        }
    }

    /** Pressed, hovered or normal; the first two blend toward the text color, as on the web side. */
    private Color thumbColor() {
        Color base = JdfColors.scrollbarThumb();
        if (isDragging) {
            return blend(base, JdfColors.text(), 0.35f);
        }
        if (isThumbRollover()) {
            return blend(base, JdfColors.text(), 0.2f);
        }
        return base;
    }

    private static Color blend(Color base, Color towards, float amount) {
        return new Color(
                Math.round(base.getRed() + (towards.getRed() - base.getRed()) * amount),
                Math.round(base.getGreen() + (towards.getGreen() - base.getGreen()) * amount),
                Math.round(base.getBlue() + (towards.getBlue() - base.getBlue()) * amount));
    }

    private static JButton zeroSizeButton() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(0, 0));
        button.setMinimumSize(new Dimension(0, 0));
        button.setMaximumSize(new Dimension(0, 0));
        button.setFocusable(false);
        return button;
    }
}
