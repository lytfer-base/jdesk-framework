package com.jdesk.frame.icon;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationInfo;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.disposer.Disposable;
import com.jdesk.core.startup.StartupSpan;
import com.jdesk.core.startup.StartupTrace;
import com.jdesk.core.startup.StartupTraces;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.scale.JdfScale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.GrayFilter;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classpath icon service for frame applications, borrowing from IntelliJ's icon system without a full IconLoader:
 * classpath lookup, SVG and bitmap loading, scaling from logical to pixel size, dark variants, disabled and
 * transparent variants, and multi-size window icons. Icons are cached by path, class loader, logical and pixel size
 * and dark flag; the cache is cleared when the theme changes.
 */
public class JdfIconManager implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(JdfIconManager.class);
    private static final int DEFAULT_ICON_SIZE = 16;
    private static final int DEFAULT_APP_ICON_SIZE = 32;
    private static final int SMALL_APP_ICON_SIZE = 16;
    private static final int[] WINDOW_ICON_SIZES = {16, 24, 32, 48, 64, 128};

    private final Application application;

    private final Map<IconKey, Icon> iconCache = new ConcurrentHashMap<>();

    /** Explicit dark-icon choice; null follows the current theme. */
    private volatile Boolean darkIconsOverride;

    public JdfIconManager() {
        this(ApplicationManager.getApplicationIfCreated());
    }

    public JdfIconManager(Application application) {
        this.application = application;
    }

    /** Loads an icon at the default logical size. */
    public Icon getIcon(String path) {
        return getIcon(path, DEFAULT_ICON_SIZE);
    }

    /** Loads a square icon at the given logical size. */
    public Icon getIcon(String path, int size) {
        return getIcon(path, size, size);
    }

    /**
     * Loads an icon through the application class loader at a logical size; it renders at the pixel size of the
     * current UI scale while reporting the logical size to Swing.
     */
    public Icon getIcon(String path, int width, int height) {
        ClassLoader loader = application != null
                ? application.getApplicationClassLoader()
                : JdfIconManager.class.getClassLoader();
        return getIcon(path, loader, width, height);
    }

    /**
     * Loads an icon through the given class loader, which keeps same-named resources of the host and plugins apart.
     * Dark themes look for a {@code _dark} variant of the file name first and fall back to the plain path.
     */
    public Icon getIcon(String path, ClassLoader loader, int width, int height) {
        String normalizedPath = normalizePath(path);
        ClassLoader effectiveLoader = loader != null ? loader : JdfIconManager.class.getClassLoader();
        int targetWidth = width > 0 ? width : DEFAULT_ICON_SIZE;
        int targetHeight = height > 0 ? height : targetWidth;
        int pixelWidth = JdfScale.scalePixelSize(targetWidth, null);
        int pixelHeight = JdfScale.scalePixelSize(targetHeight, null);
        IconKey key = new IconKey(normalizedPath, effectiveLoader, targetWidth, targetHeight,
                pixelWidth, pixelHeight, isUseDarkIcons());
        return iconCache.computeIfAbsent(key, this::loadIcon);
    }

    /** Loads the product icon at the default size, or generates a placeholder when none is configured. */
    public Icon getApplicationIcon() {
        return getApplicationIcon(DEFAULT_APP_ICON_SIZE);
    }

    /** Loads the product icon at a logical size, preferring the large icon resources. */
    public Icon getApplicationIcon(int size) {
        ApplicationInfo info = getApplicationInfo();
        String path = firstPresent(info.getIconSvgPath(), info.getIconPngPath(),
                info.getSmallIconSvgPath(), info.getSmallIconPngPath());
        if (path.isBlank()) {
            return new GeneratedProductIcon(size, info.getProductName());
        }
        return getIcon(path, size);
    }

    /** Loads the small product icon for title bars and the taskbar. */
    public Icon getSmallApplicationIcon() {
        return getSmallApplicationIcon(22);
    }

    /** Like {@link #getSmallApplicationIcon()}, at the given logical size. */
    public Icon getSmallApplicationIcon(int size) {
        ApplicationInfo info = getApplicationInfo();
        String path = firstPresent(info.getSmallIconSvgPath(), info.getSmallIconPngPath(),
                info.getIconSvgPath(), info.getIconPngPath());
        if (path.isBlank()) {
            return new GeneratedProductIcon(size, info.getProductName());
        }
        return getIcon(path, size);
    }

    /** Returns the application icon in several sizes for AWT and Swing windows; the platform picks the best fit. */
    public List<Image> getApplicationIconImages() {
        List<Image> images = new ArrayList<>();
        for (int size : WINDOW_ICON_SIZES) {
            Icon icon = size <= SMALL_APP_ICON_SIZE ? getSmallApplicationIcon() : getApplicationIcon(size);
            images.add(toImage(icon, size, size));
        }
        return List.copyOf(images);
    }

    /** Sets the application icons on {@code window}; null is ignored. */
    public void updateWindowIcon(Window window) {
        if (window == null) {
            return;
        }
        List<Image> images = getApplicationIconImages();
        if (!images.isEmpty()) {
            window.setIconImages(images);
        }
    }

    /** Returns a Swing disabled-state version of {@code icon}. */
    public Icon getDisabledIcon(Icon icon) {
        Objects.requireNonNull(icon, "icon");
        Image image = GrayFilter.createDisabledImage(toImage(icon, icon.getIconWidth(), icon.getIconHeight()));
        return new ImageIcon(image);
    }

    /** Returns a copy of {@code icon} with the given opacity, clamped to 0..1. */
    public Icon getTransparentIcon(Icon icon, float alpha) {
        Objects.requireNonNull(icon, "icon");
        float safeAlpha = Math.max(0f, Math.min(1f, alpha));
        BufferedImage image = new BufferedImage(
                Math.max(1, icon.getIconWidth()),
                Math.max(1, icon.getIconHeight()),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, safeAlpha));
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return new ImageIcon(image);
    }

    /** Returns whether dark icon variants are preferred; follows the theme unless set explicitly. */
    public boolean isUseDarkIcons() {
        Boolean override = darkIconsOverride;
        return override != null ? override : JdfColors.isDark();
    }

    /** Sets the dark-icon choice explicitly, clearing the cache when it changes. */
    public void setUseDarkIcons(boolean useDarkIcons) {
        Boolean old = darkIconsOverride;
        darkIconsOverride = useDarkIcons;
        if (old == null || old.booleanValue() != useDarkIcons) {
            clearCache();
        }
    }

    /** Clears the icon cache, typically after the theme or the icon policy changes. */
    public void clearCache() {
        iconCache.clear();
    }

    @Override
    public void dispose() {
        clearCache();
    }

    private Icon loadIcon(IconKey key) {
        StartupTrace startupTrace = StartupTraces.getCurrent();
        try (StartupSpan span = startupTrace.begin("icon.load:" + key.path)) {
            span.detail("path", key.path);
            span.detail("logicalSize", key.width + "x" + key.height);
            span.detail("pixelSize", key.pixelWidth + "x" + key.pixelHeight);
            ResourceMatch match = findResource(key.path, key.loader, key.dark);
            if (match == null) {
                startupTrace.incrementCounter("icons.missing");
                LOG.warn("Icon resource not found: {}", key.path);
                return new MissingIcon(key.width, key.height);
            }

            String extension = extension(match.path);
            span.detail("resolvedPath", match.path);
            span.detail("type", extension);
            try {
                startupTrace.incrementCounter("icons.loaded");
                if ("svg".equals(extension)) {
                    startupTrace.incrementCounter("icons.svgLoaded");
                    try (InputStream input = match.url.openStream()) {
                        BufferedImage image = SvgImageLoader.render(input, key.pixelWidth, key.pixelHeight);
                        return new LogicalImageIcon(image, key.width, key.height);
                    }
                }
                startupTrace.incrementCounter("icons.rasterLoaded");
                return loadRasterIcon(match.url, key.width, key.height, key.pixelWidth, key.pixelHeight);
            } catch (IOException | RuntimeException e) {
                // Cache a placeholder for a broken icon so it isn't parsed and thrown again on every request.
                span.failed(e);
                startupTrace.incrementCounter("icons.failed");
                LOG.warn("Icon failed to load, using a placeholder: {}", match.path, e);
                return new MissingIcon(key.width, key.height);
            }
        }
    }

    private Icon loadRasterIcon(URL url, int width, int height, int pixelWidth, int pixelHeight) throws IOException {
        BufferedImage image = ImageIO.read(url);
        if (image != null) {
            return new LogicalImageIcon(scaleImage(image, pixelWidth, pixelHeight), width, height);
        }

        ImageIcon icon = new ImageIcon(url);
        BufferedImage buffered = toImage(icon, icon.getIconWidth(), icon.getIconHeight());
        return new LogicalImageIcon(scaleImage(buffered, pixelWidth, pixelHeight), width, height);
    }

    private ResourceMatch findResource(String path, ClassLoader loader, boolean dark) {
        List<String> candidates = new ArrayList<>();
        if (dark) {
            candidates.add(withSuffixBeforeExtension(path, "_dark"));
        }
        candidates.add(path);

        for (String candidate : candidates) {
            URL resource = loader.getResource(candidate);
            if (resource != null) {
                return new ResourceMatch(candidate, resource);
            }
        }
        return null;
    }

    private ApplicationInfo getApplicationInfo() {
        if (application != null && application.getServiceContainer().hasService(ApplicationInfo.class)) {
            return application.getService(ApplicationInfo.class);
        }
        return ApplicationInfo.getInstance();
    }

    private static BufferedImage toImage(Icon icon, int width, int height) {
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        BufferedImage image = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = Math.max(0, (safeWidth - icon.getIconWidth()) / 2);
            int y = Math.max(0, (safeHeight - icon.getIconHeight()) / 2);
            icon.paintIcon(new JLabel(), g, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage scaleImage(BufferedImage source, int width, int height) {
        int safeWidth = width > 0 ? width : source.getWidth();
        int safeHeight = height > 0 ? height : source.getHeight();
        if (source.getWidth() == safeWidth && source.getHeight() == safeHeight) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(safeWidth, safeHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, safeWidth, safeHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Icon path cannot be blank");
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String withSuffixBeforeExtension(String path, String suffix) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash) {
            return path + suffix;
        }
        return path.substring(0, dot) + suffix + path.substring(dot);
    }

    private record IconKey(String path, ClassLoader loader, int width, int height,
                           int pixelWidth, int pixelHeight, boolean dark) {
    }

    /** The resource found, with the path that actually matched (possibly the dark variant). */
    private record ResourceMatch(String path, URL url) {
    }

    /** A high-resolution image painted at its logical Swing size. */
    private static final class LogicalImageIcon implements Icon {
        private final BufferedImage image;
        private final int width;
        private final int height;

        private LogicalImageIcon(BufferedImage image, int width, int height) {
            this.image = Objects.requireNonNull(image, "image");
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (image.getWidth() == width && image.getHeight() == height) {
                g.drawImage(image, x, y, null);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(image, x, y, width, height, null);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Placeholder for a missing resource, drawn as a crossed box so that bad paths stand out. */
    private static final class MissingIcon implements Icon {
        private final int width;
        private final int height;

        private MissingIcon(int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(new Color(220, 96, 96, 160));
            g.drawRect(x, y, width - 1, height - 1);
            g.drawLine(x, y, x + width - 1, y + height - 1);
            g.drawLine(x + width - 1, y, x, y + height - 1);
        }
    }

    /** Simple generated icon used when no product icon is configured. */
    private static final class GeneratedProductIcon implements Icon {
        private final int size;
        private final String productName;

        private GeneratedProductIcon(int size, String productName) {
            this.size = Math.max(16, size);
            this.productName = productName == null || productName.isBlank() ? "DF" : productName;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = Math.max(5, size / 5);
                g2.setColor(JdfColors.text());
                g2.fillRoundRect(x, y, size, size, arc, arc);
                g2.setColor(JdfColors.accent());
                g2.fillRect(x + size / 7, y + size / 7, size / 3, size / 3);
                g2.setColor(Color.WHITE);
                g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD, Math.max(9f, size * 0.42f)));
                String initials = initials(productName);
                g2.drawString(initials, x + Math.max(3, size / 5), y + Math.max(12, size * 3 / 4));
            } finally {
                g2.dispose();
            }
        }

        private static String initials(String text) {
            StringBuilder initials = new StringBuilder();
            for (String part : text.trim().split("\\s+")) {
                if (!part.isBlank()) {
                    initials.append(Character.toUpperCase(part.charAt(0)));
                }
                if (initials.length() == 2) {
                    break;
                }
            }
            return initials.isEmpty() ? "DF" : initials.toString();
        }
    }
}
