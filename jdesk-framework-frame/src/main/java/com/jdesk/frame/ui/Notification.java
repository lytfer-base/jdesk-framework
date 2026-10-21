package com.jdesk.frame.ui;

import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Balloon notification at the bottom right of a window, modeled on IntelliJ Platform's notifications: INFO, WARNING
 * or ERROR, an optional timeout (none for errors by default), optional action buttons, and a click to dismiss.
 * The title and content are rendered as HTML: escape {@code &}, {@code <} and {@code >} in user content first.
 */
public class Notification {

    public enum Type { INFO, WARNING, ERROR }

    private final Type type;
    private final String title;
    private final String content;
    private final List<NotificationAction> actions = new ArrayList<>();
    private long timeoutMs = 5000;
    private boolean dismissed = false;

    private Notification(Type type, String title, String content) {
        this.type = type;
        this.title = title;
        this.content = content;
    }

    // --- Factories ---

    public static Notification info(String title, String content) {
        return new Notification(Type.INFO, title, content);
    }

    public static Notification warning(String title, String content) {
        return new Notification(Type.WARNING, title, content);
    }

    public static Notification error(String title, String content) {
        return new Notification(Type.ERROR, title, content).withTimeout(0);
    }

    // --- Configuration ---

    public Notification withTimeout(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    public Notification withAction(String text, Runnable callback) {
        actions.add(new NotificationAction(text, callback));
        return this;
    }

    // --- Display ---

    /** Shows the notification near the owner's bottom-right corner. */
    public void show(JFrame owner) {
        JDialog balloon = createBalloon(owner);
        balloon.setVisible(true);

        if (timeoutMs > 0) {
            Timer timer = new Timer((int) timeoutMs, e -> dismissBalloon(balloon));
            timer.setRepeats(false);
            timer.start();
        }
    }

    private JDialog createBalloon(JFrame owner) {
        JDialog dialog = new JDialog(owner, false);
        dialog.setUndecorated(true);
        dialog.setAlwaysOnTop(true);

        JPanel panel = new JPanel(new BorderLayout(JdfScale.scale(8), JdfScale.scale(8)));
        panel.setBorder(BorderFactory.createCompoundBorder(
                JdfUi.lineBorder(getBorderColor()),
                JdfUi.empty(10, 12, 10, 12)));
        panel.setBackground(getBackgroundColor());

        JPanel header = new JPanel(new BorderLayout(JdfScale.scale(6), 0));
        header.setOpaque(false);

        JLabel iconLabel = new JLabel(getIcon());
        iconLabel.setBorder(JdfUi.empty(0, 0, 0, 4));
        header.add(iconLabel, BorderLayout.WEST);

        JLabel titleLabel = new JLabel("<html><b>" + title + "</b></html>");
        titleLabel.setFont(JdfFonts.label(13f));
        titleLabel.setForeground(getTitleColor());
        header.add(titleLabel, BorderLayout.CENTER);

        JLabel closeLabel = new JLabel("✕");
        closeLabel.setForeground(new Color(150, 150, 150));
        closeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) { dismissBalloon(dialog); }
        });
        header.add(closeLabel, BorderLayout.EAST);

        panel.add(header, BorderLayout.NORTH);

        if (content != null && !content.isEmpty()) {
            JLabel contentLabel = new JLabel("<html><body style='width: " + JdfScale.scale(280) + "px;'>" + content + "</body></html>");
            contentLabel.setFont(JdfFonts.label(12f));
            contentLabel.setForeground(new Color(80, 80, 80));
            contentLabel.setBorder(JdfUi.empty(4, 24, 0, 0));
            panel.add(contentLabel, BorderLayout.CENTER);
        }

        if (!actions.isEmpty()) {
            JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JdfScale.scale(6), 0));
            actionPanel.setOpaque(false);
            actionPanel.setBorder(JdfUi.empty(8, 0, 0, 0));
            for (NotificationAction na : actions) {
                JButton btn = new JButton(na.text);
                btn.setFont(JdfFonts.small());
                btn.addActionListener(e -> {
                    na.callback.run();
                    dismissBalloon(dialog);
                });
                actionPanel.add(btn);
            }
            panel.add(actionPanel, BorderLayout.SOUTH);
        }

        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) { dismissBalloon(dialog); }
        });
        panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), JdfScale.scale(320)), dialog.getHeight());

        positionBalloon(dialog, owner);

        return dialog;
    }

    private void positionBalloon(JDialog dialog, JFrame owner) {
        if (owner == null) {
            dialog.setLocationRelativeTo(null);
            return;
        }

        Point ownerLoc = owner.isShowing() ? owner.getLocationOnScreen() : owner.getLocation();
        Dimension ownerSize = owner.getSize();
        int x = ownerLoc.x + ownerSize.width - dialog.getWidth() - JdfScale.scale(20);
        int y = ownerLoc.y + ownerSize.height - dialog.getHeight() - JdfScale.scale(40);
        dialog.setLocation(x, y);
    }

    private void dismissBalloon(JDialog dialog) {
        if (dismissed) return;
        dismissed = true;
        dialog.dispose();
    }

    // --- Visual helpers ---

    private String getIcon() {
        return switch (type) {
            case INFO -> "ℹ";
            case WARNING -> "⚠";
            case ERROR -> "✖";
        };
    }

    private Color getBorderColor() {
        return switch (type) {
            case INFO -> new Color(88, 166, 255);
            case WARNING -> new Color(210, 153, 34);
            case ERROR -> new Color(248, 81, 73);
        };
    }

    private Color getBackgroundColor() {
        return switch (type) {
            case INFO -> new Color(235, 245, 255);
            case WARNING -> new Color(255, 248, 230);
            case ERROR -> new Color(255, 235, 235);
        };
    }

    private Color getTitleColor() {
        return switch (type) {
            case INFO -> new Color(30, 30, 30);
            case WARNING -> new Color(180, 120, 20);
            case ERROR -> new Color(200, 40, 40);
        };
    }

    // --- Internal types ---

    private record NotificationAction(String text, Runnable callback) {}
}
