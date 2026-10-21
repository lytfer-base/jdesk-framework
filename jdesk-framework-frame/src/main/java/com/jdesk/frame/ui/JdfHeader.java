package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.disposer.Disposer;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.message.MessageBusConnection;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.jbr.JbrWindowDecorations;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.laf.JdfThemeListener;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.Icon;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Lightweight application header with the product icon, the window title and an action area on the right. Where the
 * JetBrains Runtime supports custom title bars it keeps JBR in step with its height and the space of the system
 * window buttons, following window size and state changes; elsewhere it is a plain Swing panel.
 */
public class JdfHeader extends JPanel {

    public static final int DEFAULT_HEIGHT = 38;

    /** Logical size of the product icon, one step smaller than the taskbar and window icons. */
    private static final int PRODUCT_ICON_SIZE = 18;

    private final Application application;
    private final JFrame frame;
    private final JdfHeaderOptions options;
    private final JLabel productIconLabel = new JLabel();
    private final JLabel moduleMarkLabel = new ModuleMarkLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();

    /** Space reserved for the native window buttons on either side. */
    private final JPanel leftInset = new JPanel();
    private final JPanel rightInset = new JPanel();

    /** Action area on the right, for entries such as search, settings or account. */
    private final JPanel actionPanel = new JPanel(new GridBagLayout());
    private final ComponentAdapter componentListener = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent e) {
            SwingUtilities.invokeLater(JdfHeader.this::updateCustomTitleBar);
        }
    };
    private final WindowAdapter windowListener = new WindowAdapter() {
        @Override
        public void windowStateChanged(WindowEvent e) {
            updateCustomTitleBar();
        }
    };

    private JbrWindowDecorations.TitleBarHandle titleBarHandle = JbrWindowDecorations.TitleBarHandle.missing();

    /** Theme subscription from addNotify to removeNotify; null otherwise and without an application. */
    private MessageBusConnection themeConnection;

    public JdfHeader(Application application, JFrame frame) {
        this(application, frame, JdfHeaderOptions.defaults());
    }

    public JdfHeader(Application application, JFrame frame, JdfHeaderOptions options) {
        super(new BorderLayout());
        this.application = application;
        this.frame = frame;
        this.options = options == null ? JdfHeaderOptions.defaults() : options;
        setPreferredSize(new Dimension(0, JdfScale.scale(this.options.height())));
        setMinimumSize(new Dimension(0, JdfScale.scale(this.options.height())));
        buildUi();
        setTitleText(this.options.title());
        setSubtitleText(this.options.subtitle());
        updateTheme();
    }

    public void setTitleText(String title) {
        titleLabel.setText(title == null ? "" : title);
    }

    public String getTitleText() {
        return titleLabel.getText();
    }

    public void setSubtitleText(String subtitle) {
        subtitleLabel.setText(subtitle == null ? "" : subtitle);
        subtitleLabel.setVisible(!subtitleLabel.getText().isBlank());
    }

    public String getSubtitleText() {
        return subtitleLabel.getText();
    }

    public int getHeaderHeight() {
        return options.height();
    }

    public void addHeaderAction(JComponent component) {
        component.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = actionPanel.getComponentCount();
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.CENTER;
        if (constraints.gridx > 0) {
            constraints.insets = JdfUi.insets(0, 4, 0, 0);
        }
        actionPanel.add(component, constraints);
        revalidate();
        repaint();
    }

    public boolean isJbrTitleBarActive() {
        return titleBarHandle.isAvailable();
    }

    public void primeCustomTitleBar() {
        JbrWindowDecorations.primeCustomTitleBar(frame, JdfColors.isDark());
    }

    public void updateTheme() {
        // Keep a fixed background; don't switch it with window activation.
        Color background = JdfColors.background();
        setBackground(background);
        setBorder(JdfUi.bottomBorderSlim());
        titleLabel.setForeground(JdfColors.text());
        subtitleLabel.setForeground(JdfColors.mutedText());
        moduleMarkLabel.setForeground(JdfColors.accent());
        moduleMarkLabel.setBackground(JdfColors.activeBackground());
        moduleMarkLabel.setBorder(JdfUi.empty());
        setPanelBackground(leftInset, background);
        setPanelBackground(rightInset, background);
        setPanelBackground(actionPanel, background);
        updateProductIcon();
        if (titleBarHandle.isAvailable()) {
            titleBarHandle.setControlsDark(JdfColors.isDark());
            titleBarHandle.setControlsBackground(background, JdfColors.selectionBackground(), JdfColors.border());
        }
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        frame.addWindowStateListener(windowListener);
        frame.addComponentListener(componentListener);
        subscribeThemeChanges();
        installCustomTitleBar();
        SwingUtilities.invokeLater(this::updateCustomTitleBar);
    }

    @Override
    public void removeNotify() {
        titleBarHandle.uninstall();
        unsubscribeThemeChanges();
        frame.removeWindowStateListener(windowListener);
        frame.removeComponentListener(componentListener);
        super.removeNotify();
    }

    /**
     * Subscribes to theme changes: {@link #updateTheme()} sets plain colors, not {@code ColorUIResource}s, which
     * {@code updateComponentTreeUI} doesn't replace, so without this the header would keep its first theme.
     */
    private void subscribeThemeChanges() {
        if (application == null || application.isDisposed() || themeConnection != null) {
            return;
        }
        // Minimal hosts and tests may have no message bus; skip the subscription rather than break addNotify.
        MessageBus bus = application.getMessageBus();
        if (bus == null) {
            return;
        }
        themeConnection = bus.connect();
        // Publishing is synchronous but may happen off the EDT; updateTheme() repaints by itself.
        themeConnection.subscribe(JdfThemeListener.class,
                (oldTheme, newTheme) -> SwingUtilities.invokeLater(this::updateTheme));
    }

    private void unsubscribeThemeChanges() {
        if (themeConnection == null) {
            return;
        }
        Disposer.dispose(themeConnection);
        themeConnection = null;
    }

    @Override
    public void doLayout() {
        super.doLayout();
        updateCustomTitleBar();
    }

    public static Window windowFor(JComponent component) {
        return component == null ? null : SwingUtilities.getWindowAncestor(component);
    }

    private void buildUi() {
        JPanel titlePanel = new JPanel(new GridBagLayout());
        titlePanel.setOpaque(false);
        titlePanel.setBorder(JdfUi.empty(0, 15, 0, 0));
        GridBagConstraints titleConstraints = new GridBagConstraints();
        titleConstraints.gridy = 0;
        titleConstraints.anchor = GridBagConstraints.CENTER;
        titleConstraints.insets = JdfUi.insets(2, 0, 0, 8);
        titlePanel.add(leftInset, titleConstraints);
        if (!options.moduleMarkText().isBlank()) {
            moduleMarkLabel.setText(options.moduleMarkText());
            moduleMarkLabel.setHorizontalAlignment(SwingConstants.CENTER);
            moduleMarkLabel.setVerticalAlignment(SwingConstants.CENTER);
            moduleMarkLabel.setOpaque(false);
            moduleMarkLabel.setPreferredSize(JdfUi.size(26, 26));
            moduleMarkLabel.setMinimumSize(JdfUi.size(26, 26));
            moduleMarkLabel.setFont(JdfFonts.derive(moduleMarkLabel.getFont(), Font.BOLD, 16f));
            titleConstraints.gridx++;
            titlePanel.add(moduleMarkLabel, titleConstraints);
        }
        else if (options.showProductIcon()) {
            productIconLabel.setIcon(new ProductIcon());
            titleConstraints.gridx++;
            titlePanel.add(productIconLabel, titleConstraints);
        }

        titleLabel.setFont(JdfFonts.derive(titleLabel.getFont(), Font.PLAIN, 13f));
        subtitleLabel.setFont(JdfFonts.derive(subtitleLabel.getFont(), Font.PLAIN, 11f));
        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        subtitleLabel.setAlignmentX(LEFT_ALIGNMENT);
        textPanel.add(titleLabel);
        textPanel.add(subtitleLabel);
        titleConstraints.gridx++;
        titleConstraints.insets = JdfUi.insets(0, 0, 0, 4);
        titlePanel.add(textPanel, titleConstraints);

        actionPanel.setOpaque(false);
        actionPanel.setBorder(JdfUi.empty(0, 4));

        JPanel eastPanel = new JPanel(new BorderLayout());
        eastPanel.setOpaque(false);
        eastPanel.add(actionPanel, BorderLayout.WEST);
        eastPanel.add(rightInset, BorderLayout.EAST);

        add(titlePanel, BorderLayout.WEST);
        add(eastPanel, BorderLayout.EAST);
    }

    private void installCustomTitleBar() {
        titleBarHandle = JbrWindowDecorations.installCustomTitleBar(frame, JdfScale.scale(options.height()), JdfColors.isDark());
        updateCustomTitleBar();
        updateTheme();
    }

    private void updateCustomTitleBar() {
        if (titleBarHandle.isAvailable()) {
            titleBarHandle.setHeight(Math.max(1, getHeight() - getInsets().bottom));
            titleBarHandle.install();
        }
        updateNativeControlInsets();
    }

    private void updateNativeControlInsets() {
        int left = titleBarHandle.isAvailable() ? titleBarHandle.getLeftInset() : 0;
        int right = titleBarHandle.isAvailable() ? titleBarHandle.getRightInset() : 0;
        setInsetWidth(leftInset, left);
        setInsetWidth(rightInset, right);
    }

    private void updateProductIcon() {
        if (productIconLabel.getParent() == null) {
            return;
        }
        if (application == null) {
            productIconLabel.setIcon(new ProductIcon());
            return;
        }
        try {
            productIconLabel.setIcon(application.getService(JdfIconManager.class).getSmallApplicationIcon(PRODUCT_ICON_SIZE));
        }
        catch (IllegalStateException ignored) {
            productIconLabel.setIcon(new ProductIcon());
        }
    }

    private static void setPanelBackground(JPanel panel, Color background) {
        panel.setBackground(background);
        panel.setOpaque(false);
        for (java.awt.Component child : panel.getComponents()) {
            child.setBackground(background);
            child.setForeground(JdfColors.text());
        }
    }

    private static void setInsetWidth(JPanel panel, int width) {
        Dimension size = new Dimension(width, 1);
        panel.setPreferredSize(size);
        panel.setMinimumSize(size);
        panel.setMaximumSize(new Dimension(width, Integer.MAX_VALUE));
    }

    private static final class ModuleMarkLabel extends JLabel {
        private ModuleMarkLabel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = JdfScale.scale(11);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                g2.setColor(JdfColors.border());
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            finally {
                g2.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    /** Fallback product icon for when no icon resource is available. */
    private static final class ProductIcon implements Icon {

        private static final int SIZE = PRODUCT_ICON_SIZE;

        @Override
        public int getIconWidth() {
            return JdfScale.scale(SIZE);
        }

        @Override
        public int getIconHeight() {
            return JdfScale.scale(SIZE);
        }

        @Override
        public void paintIcon(java.awt.Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = JdfScale.scale(SIZE);
                g2.setColor(JdfColors.text());
                g2.fillRoundRect(x, y, size, size, JdfScale.scale(5), JdfScale.scale(5));
                g2.setColor(JdfColors.accent());
                g2.fillRect(x + JdfScale.scale(3), y + JdfScale.scale(3), JdfScale.scale(7), JdfScale.scale(7));
                g2.setColor(Color.WHITE);
                g2.setFont(JdfFonts.derive(c.getFont(), Font.BOLD, 10f));
                g2.drawString("DF", x + JdfScale.scale(5), y + JdfScale.scale(16));
            }
            finally {
                g2.dispose();
            }
        }
    }
}
