package com.jdesk.frame.ui.scale;

import com.jdesk.core.runtime.RuntimeParamRegistry;
import com.jdesk.frame.runtime.params.FrameRuntimeParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * UI scaling for JDesk Swing applications, split as in IntelliJ: the system scale is the device pixel density and
 * the user scale is the application's explicit preference. When the JRE manages HiDPI, Swing layout is already in
 * logical pixels and gets only the user scale; otherwise layout also applies the detected system DPI scale.
 */
public final class JdfScale {

    private static final Logger LOG = LoggerFactory.getLogger(JdfScale.class);
    private static final float DISCRETE_SCALE_RESOLUTION = 0.25f;
    private static final float MIN_USER_SCALE = 0.75f;
    private static final float MAX_USER_SCALE = 4.0f;

    private static volatile boolean installed;
    /** Set when the configuration names a scale, which the system font then can't override. */
    private static volatile boolean explicitUserScale;
    private static volatile float userScale = 1.0f;
    private static volatile float systemScale = 1.0f;
    private static volatile boolean jreManagedHiDpi = true;

    private JdfScale() {
    }

    /** Detects the scales early; every query calls it too, so callers needn't care about initialization order. */
    public static void installEarly() {
        if (installed) {
            return;
        }
        synchronized (JdfScale.class) {
            if (installed) {
                return;
            }
            ScaleData scaleData = computeScaleData();
            systemScale = scaleData.systemScale();
            jreManagedHiDpi = scaleData.jreManagedHiDpi();
            ScaleValue scaleValue = computeUserScale();
            userScale = scaleValue.scale();
            explicitUserScale = scaleValue.explicit();
            installed = true;
            if (isVerbose()) {
                LOG.info("JDesk UI scale initialized: user={}, system={}, jreManagedHiDpi={}, layout={}",
                        userScale, systemScale, jreManagedHiDpi, layoutScale());
            }
        }
    }

    /** Returns the application's user scale. */
    public static float userScale() {
        installEarly();
        return userScale;
    }

    /** Returns the system scale detected for the default screen. */
    public static float systemScale() {
        installEarly();
        return systemScale;
    }

    /** Returns the system scale of the component's screen, or the default one when unknown. */
    public static float systemScale(Component component) {
        installEarly();
        if (component == null) {
            return systemScale;
        }
        return systemScale(component.getGraphicsConfiguration());
    }

    /** Returns the device pixel scale, for icons and bitmaps rendered in device pixels. */
    public static float pixelScale(Component component) {
        return systemScale(component) * userScale();
    }

    /** Scales an integer size by the layout scale. */
    public static int scale(int value) {
        installEarly();
        return Math.round(layoutScale() * value);
    }

    /** Scales a float size by the layout scale. */
    public static float scale(float value) {
        installEarly();
        return layoutScale() * value;
    }

    /** Scales a font size, correcting some scale steps so that text doesn't look smaller than the layout. */
    public static int scaleFontSize(float fontSize) {
        installEarly();
        float factor = layoutScale();
        float scaled = fontSize * fontScaleCorrection(factor);
        return Math.max(1, Math.round(scaled));
    }

    /** Returns whether the JRE maps HiDPI to logical pixels. */
    public static boolean isJreManagedHiDpi() {
        installEarly();
        return jreManagedHiDpi;
    }

    /** Converts a logical size to device pixels, mainly for rendering icons at high resolution. */
    public static int scalePixelSize(int logicalSize, Component component) {
        return Math.max(1, Math.round(logicalSize * pixelScale(component)));
    }

    /** Returns whether the configuration names a user scale. */
    public static boolean hasExplicitUserScale() {
        installEarly();
        return explicitUserScale;
    }

    /** Derives the user scale from the OS font size unless {@link FrameRuntimeParams#UI_SCALE_KEY} sets it. */
    public static void updateFromSystemFont(Font systemFont) {
        installEarly();
        if (systemFont == null || explicitUserScale) {
            return;
        }
        float fontScale = discreteScale(systemFont.getSize2D() / defaultSystemFontSize());
        if (fontScale < MIN_USER_SCALE || fontScale > MAX_USER_SCALE) {
            fontScale = 1.0f;
        }
        synchronized (JdfScale.class) {
            if (!explicitUserScale && !approximately(userScale, fontScale)) {
                userScale = fontScale;
                if (isVerbose()) {
                    LOG.info("JDesk UI scale updated from system font: font={}, size={}, user={}, layout={}",
                            systemFont.getFamily(), systemFont.getSize2D(), userScale, layoutScale());
                }
            }
        }
    }

    /** Resets the static scale state so that detection runs again; tests only. */
    public static void resetForTests() {
        synchronized (JdfScale.class) {
            installed = false;
            explicitUserScale = false;
            userScale = 1.0f;
            systemScale = 1.0f;
            jreManagedHiDpi = true;
        }
    }

    private static float layoutScale() {
        return jreManagedHiDpi ? userScale : systemScale * userScale;
    }

    private static float fontScaleCorrection(float factor) {
        if (approximately(factor, 1.25f)) {
            return 1.34f;
        }
        if (approximately(factor, 1.75f)) {
            return 1.67f;
        }
        return factor;
    }

    private static ScaleData computeScaleData() {
        float transformScale = defaultTransformScale();
        float screenScale = screenDpiScale();
        boolean jreManaged = transformScale > 1.0f + 0.01f;
        float detectedSystemScale = jreManaged ? transformScale : screenScale;
        if (detectedSystemScale <= 0.0f) {
            detectedSystemScale = 1.0f;
        }
        if (isVerbose()) {
            LOG.info("JDesk UI scale detection: transform={}, screenDpiScale={}",
                    transformScale, screenScale);
        }
        return new ScaleData(detectedSystemScale, jreManaged || approximately(detectedSystemScale, 1.0f));
    }

    private static float systemScale(GraphicsConfiguration graphicsConfiguration) {
        if (graphicsConfiguration == null || graphicsConfiguration.getDevice().getType() == GraphicsDevice.TYPE_PRINTER) {
            return systemScale;
        }
        AffineTransform transform = graphicsConfiguration.getDefaultTransform();
        double scale = transform == null ? 1.0d : Math.abs(transform.getScaleX());
        return scale > 0.0d ? (float) scale : systemScale;
    }

    private static float defaultTransformScale() {
        if (GraphicsEnvironment.isHeadless()) {
            return 1.0f;
        }
        try {
            GraphicsDevice device = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            GraphicsConfiguration configuration = device == null ? null : device.getDefaultConfiguration();
            if (configuration == null || configuration.getDevice().getType() == GraphicsDevice.TYPE_PRINTER) {
                return 1.0f;
            }
            AffineTransform transform = configuration.getDefaultTransform();
            double scale = transform == null ? 1.0d : Math.abs(transform.getScaleX());
            return scale > 0.0d ? (float) scale : 1.0f;
        } catch (HeadlessException ignored) {
            return 1.0f;
        }
    }

    private static float screenDpiScale() {
        try {
            int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
            return discreteScale(dpi / 96.0f);
        } catch (HeadlessException ignored) {
            return 1.0f;
        }
    }

    private static ScaleValue computeUserScale() {
        String property = RuntimeParamRegistry.getString(FrameRuntimeParams.UI_SCALE, null);
        if (property == null || property.isBlank() || "auto".equalsIgnoreCase(property.trim())) {
            return new ScaleValue(1.0f, false);
        }
        try {
            float scale = Float.parseFloat(property.trim());
            if (scale < MIN_USER_SCALE || scale > MAX_USER_SCALE) {
                LOG.warn("{}={} is outside the supported range {}..{}; using 1.0", FrameRuntimeParams.UI_SCALE_KEY, property, MIN_USER_SCALE, MAX_USER_SCALE);
                return new ScaleValue(1.0f, false);
            }
            return new ScaleValue(discreteScale(scale), true);
        } catch (NumberFormatException e) {
            LOG.warn("{}={} is not a valid float; using 1.0", FrameRuntimeParams.UI_SCALE_KEY, property);
            return new ScaleValue(1.0f, false);
        }
    }

    private static float defaultSystemFontSize() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac") ? 13.0f : 12.0f;
    }

    private static float discreteScale(float scale) {
        return Math.round(scale / DISCRETE_SCALE_RESOLUTION) * DISCRETE_SCALE_RESOLUTION;
    }

    private static boolean approximately(float left, float right) {
        return Math.abs(left - right) < 0.001f;
    }

    private static boolean isVerbose() {
        try {
            return RuntimeParamRegistry.getBoolean(FrameRuntimeParams.UI_SCALE_VERBOSE, false);
        } catch (IllegalArgumentException e) {
            String value = RuntimeParamRegistry.contains(FrameRuntimeParams.UI_SCALE_VERBOSE_KEY)
                    ? RuntimeParamRegistry.getString(FrameRuntimeParams.UI_SCALE_VERBOSE_KEY)
                    : null;
            LOG.warn("{}={} is not a valid boolean; using false", FrameRuntimeParams.UI_SCALE_VERBOSE_KEY, value);
            return false;
        }
    }

    private record ScaleData(float systemScale, boolean jreManagedHiDpi) {
    }

    private record ScaleValue(float scale, boolean explicit) {
    }
}
