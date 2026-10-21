package com.jdesk.frame.ui.components;

import javax.swing.border.AbstractBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lightweight border painted one device pixel thick, for hairline separators at high DPI.
 * A border that follows the theme must take a {@link Supplier}, read at every paint: theme switches go through
 * {@code updateComponentTreeUI}, which replaces only {@code UIResource} borders, so a fixed color would go stale.
 */
public final class SlimBorder extends AbstractBorder {

    private final Insets insets;
    private final Supplier<Color> color;

    /** Creates a border with a fixed color, for colors that don't follow the theme. */
    public SlimBorder(Insets insets, Color color) {
        this(insets, constant(Objects.requireNonNull(color, "color")));
    }

    /** Creates a border whose {@code color} is read at every paint, so that it follows theme switches. */
    public SlimBorder(Insets insets, Supplier<Color> color) {
        this.insets = copy(Objects.requireNonNull(insets, "insets"));
        this.color = Objects.requireNonNull(color, "color");
    }

    @Override
    public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            double scaleX = axisScale(g2.getTransform().getScaleX());
            double scaleY = axisScale(g2.getTransform().getScaleY());
            double verticalHairline = 1.0d / scaleX;
            double horizontalHairline = 1.0d / scaleY;

            g2.setColor(color.get());
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            paintTop(g2, x, y, width, horizontalHairline);
            paintLeft(g2, x, y, height, verticalHairline);
            paintBottom(g2, x, y, width, height, horizontalHairline);
            paintRight(g2, x, y, width, height, verticalHairline);
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Insets getBorderInsets(Component component) {
        return copy(insets);
    }

    @Override
    public Insets getBorderInsets(Component component, Insets target) {
        target.top = insets.top;
        target.left = insets.left;
        target.bottom = insets.bottom;
        target.right = insets.right;
        return target;
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    private void paintTop(Graphics2D g2, int x, int y, int width, double hairline) {
        if (insets.top > 0) {
            g2.fill(new Rectangle2D.Double(x, y, width, Math.min(insets.top, hairline)));
        }
    }

    private void paintLeft(Graphics2D g2, int x, int y, int height, double hairline) {
        if (insets.left > 0) {
            g2.fill(new Rectangle2D.Double(x, y, Math.min(insets.left, hairline), height));
        }
    }

    private void paintBottom(Graphics2D g2, int x, int y, int width, int height, double hairline) {
        if (insets.bottom > 0) {
            double thickness = Math.min(insets.bottom, hairline);
            g2.fill(new Rectangle2D.Double(x, y + height - thickness, width, thickness));
        }
    }

    private void paintRight(Graphics2D g2, int x, int y, int width, int height, double hairline) {
        if (insets.right > 0) {
            double thickness = Math.min(insets.right, hairline);
            g2.fill(new Rectangle2D.Double(x + width - thickness, y, thickness, height));
        }
    }

    private static Supplier<Color> constant(Color color) {
        return () -> color;
    }

    private static Insets copy(Insets source) {
        return new Insets(source.top, source.left, source.bottom, source.right);
    }

    private static double axisScale(double scale) {
        double absoluteScale = Math.abs(scale);
        return absoluteScale > 0.0d ? absoluteScale : 1.0d;
    }
}
