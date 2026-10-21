package com.jdesk.frame.ui.components;

import com.jdesk.frame.laf.JdfThemePalette;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JdfToggleIconTest {

    private static final String WEAK_TEXT = "Jdf.weakText";
    private static final String[] THEME_TOKENS = {
            JdfThemePalette.CONTROL_BACKGROUND,
            JdfThemePalette.DISABLED_TEXT,
            WEAK_TEXT
    };

    @Test
    void enabledUncheckedOutlineKeepsItsWeakOutlineColorWhenDisabledTextGetsLighter() {
        Object[] originalValues = new Object[THEME_TOKENS.length];
        for (int index = 0; index < THEME_TOKENS.length; index++) {
            originalValues[index] = UIManager.get(THEME_TOKENS[index]);
        }
        try {
            UIManager.put(JdfThemePalette.CONTROL_BACKGROUND, Color.WHITE);
            UIManager.put(JdfThemePalette.DISABLED_TEXT, new Color(0xB6, 0xB9, 0xC2));
            UIManager.put(WEAK_TEXT, new Color(0x81, 0x85, 0x94));

            BufferedImage image = new BufferedImage(14, 14, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                JdfToggleIcon.checkBox().paintIcon(new JCheckBox(), graphics, 0, 0);
            } finally {
                graphics.dispose();
            }

            assertTrue(contains(image, new Color(0x81, 0x85, 0x94)),
                    "an enabled unchecked box should keep its weakText outline, not the new disabled text color");
        } finally {
            for (int index = 0; index < THEME_TOKENS.length; index++) {
                UIManager.put(THEME_TOKENS[index], originalValues[index]);
            }
        }
    }

    private static boolean contains(BufferedImage image, Color color) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) == color.getRGB()) {
                    return true;
                }
            }
        }
        return false;
    }
}
