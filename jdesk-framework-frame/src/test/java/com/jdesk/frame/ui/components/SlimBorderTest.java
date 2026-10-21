package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfThemePalette;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SlimBorderTest {

    /** The actual Jdf.border values of idea-light and idea-dark. */
    private static final Color LIGHT_THEME_BORDER = new Color(0xE0, 0xE1, 0xE4);
    private static final Color DARK_THEME_BORDER = new Color(0x3A, 0x3C, 0x3E);

    @Test
    void bottomBorderSlimCreatesHairlineBottomBorderWithOneLogicalInset() {
        Border border = JdfUi.bottomBorderSlim();

        SlimBorder slimBorder = assertInstanceOf(SlimBorder.class, border);
        Insets insets = slimBorder.getBorderInsets(new JPanel());

        assertEquals(0, insets.top);
        assertEquals(0, insets.left);
        assertEquals(1, insets.bottom);
        assertEquals(0, insets.right);
    }

    @Test
    void slimBorderPaintsOneDevicePixelAtTwoTimesScale() {
        SlimBorder border = new SlimBorder(new Insets(0, 0, 1, 0), Color.RED);
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.scale(2.0d, 2.0d);
            border.paintBorder(new JPanel(), graphics, 0, 0, 10, 10);
        } finally {
            graphics.dispose();
        }

        assertEquals(Color.RED.getRGB(), image.getRGB(10, 19));
        assertEquals(0, image.getRGB(10, 18));
    }

    @Test
    void bottomBorderSlimTakesTheThemeColorWhenPaintingNotWhenConstructed() {
        // SlimBorder isn't a UIResource, so a theme switch never replaces it; it must read the color when painting.
        Color original = UIManager.getColor(JdfThemePalette.BORDER);
        try {
            UIManager.put(JdfThemePalette.BORDER, LIGHT_THEME_BORDER);
            Border border = JdfUi.bottomBorderSlim();

            UIManager.put(JdfThemePalette.BORDER, DARK_THEME_BORDER);

            assertEquals(DARK_THEME_BORDER.getRGB(), bottomPixelOf(border),
                    "the bottom line didn't follow the theme switch and kept the color from construction");
        } finally {
            UIManager.put(JdfThemePalette.BORDER, original);
        }
    }

    /** Returns the color actually painted at the middle of the bottom edge, at 1:1 scale. */
    private static int bottomPixelOf(Border border) {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            border.paintBorder(new JPanel(), graphics, 0, 0, 20, 20);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(10, 19);
    }
}
