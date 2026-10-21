package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * Styling helpers for Swing components: themed borders, scaled gaps and sizes, and refreshing a component tree's
 * theme, so frame components apply JDesk colors and scaling consistently.
 */
public final class JdfUi {

    private JdfUi() {
    }

    public static Border border() {
        return lineBorder(JdfColors.border());
    }

    public static Border bottomBorder() {
        return matteBorder(0, 0, 1, 0, JdfColors.border());
    }

    /**
     * Returns a hairline bottom border that follows the theme. Keep passing a method reference rather than a color:
     * the border isn't a {@code UIResource}, so only a color read at paint time survives theme switches.
     */
    public static Border bottomBorderSlim() {
        return new SlimBorder(new Insets(0, 0, 1, 0), JdfColors::border);
    }

    public static Border topBorder() {
        return matteBorder(1, 0, 0, 0, JdfColors.border());
    }

    public static Border empty() {
        return BorderFactory.createEmptyBorder();
    }

    public static Border empty(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(
                JdfScale.scale(top),
                JdfScale.scale(left),
                JdfScale.scale(bottom),
                JdfScale.scale(right));
    }

    public static Border empty(int topBottom, int leftRight) {
        return empty(topBottom, leftRight, topBottom, leftRight);
    }

    public static Border empty(int all) {
        return empty(all, all, all, all);
    }

    public static Insets insets(int top, int left, int bottom, int right) {
        return new Insets(
                JdfScale.scale(top),
                JdfScale.scale(left),
                JdfScale.scale(bottom),
                JdfScale.scale(right));
    }

    public static Insets insets(int all) {
        return insets(all, all, all, all);
    }

    public static Insets insets(int topBottom, int leftRight) {
        return insets(topBottom, leftRight, topBottom, leftRight);
    }

    public static Dimension size(int width, int height) {
        return new Dimension(JdfScale.scale(width), JdfScale.scale(height));
    }

    public static Dimension dimension(int width, int height) {
        return size(width, height);
    }

    public static Border lineBorder(Color color) {
        return BorderFactory.createLineBorder(color, Math.max(1, JdfScale.scale(1)));
    }

    public static Border matteBorder(int top, int left, int bottom, int right, Color color) {
        return BorderFactory.createMatteBorder(
                JdfScale.scale(top),
                JdfScale.scale(left),
                JdfScale.scale(bottom),
                JdfScale.scale(right),
                color);
    }

    public static Component strut(int width) {
        return Box.createHorizontalStrut(JdfScale.scale(width));
    }

    public static Component verticalStrut(int height) {
        return Box.createVerticalStrut(JdfScale.scale(height));
    }

    /** Refreshes a component tree's theme: {@link JdfThemedComponent}s update themselves, others get the text color. */
    public static void updateTreeTheme(Component component) {
        if (component == null) {
            return;
        }
        if (component instanceof JdfThemedComponent themedComponent) {
            themedComponent.updateJdfTheme();
        }
        else if (component instanceof JComponent jComponent) {
            jComponent.setForeground(JdfColors.text());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                updateTreeTheme(child);
            }
        }
    }
}
