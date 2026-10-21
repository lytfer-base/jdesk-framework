package com.jdesk.frame.ui;

import com.jdesk.core.i18n.I18n;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight builder of modal dialogs, a simple stand-in for IntelliJ Platform's {@code DialogWrapper}: a message
 * or a custom center component, custom and default buttons, and a preferred size. A button closes the dialog after
 * running its action.
 * The message is rendered as HTML: escape {@code &}, {@code <} and {@code >} in user content first.
 */
public class DialogBuilder {

    private final String title;
    private String message;
    private JComponent centerComponent;
    private final List<JButton> buttons = new ArrayList<>();
    private JButton defaultButton;
    private Dimension preferredSize;

    private boolean result = false;
    private boolean wasCancelled = true;

    private DialogBuilder(String title) {
        this.title = title;
    }

    public static DialogBuilder create(String title) {
        return new DialogBuilder(title);
    }

    /** Sets the message, an HTML fragment shown in the dialog body. */
    public DialogBuilder withMessage(String message) {
        this.message = message;
        return this;
    }

    public DialogBuilder withCenterComponent(JComponent component) {
        this.centerComponent = component;
        return this;
    }

    public DialogBuilder withPreferredSize(int width, int height) {
        this.preferredSize = JdfUi.size(width, height);
        return this;
    }

    public DialogBuilder withOkButton() {
        JButton okButton = new JButton(I18n.message("frame.dialog.ok"));
        okButton.addActionListener(e -> {
            result = true;
            wasCancelled = false;
            closeParent(okButton);
        });
        buttons.add(okButton);
        this.defaultButton = okButton;
        return this;
    }

    public DialogBuilder withCancelButton() {
        JButton cancelButton = new JButton(I18n.message("frame.dialog.cancel"));
        cancelButton.addActionListener(e -> {
            result = false;
            wasCancelled = true;
            closeParent(cancelButton);
        });
        buttons.add(cancelButton);
        return this;
    }

    public DialogBuilder withButton(String text, Runnable action, boolean isDefault) {
        JButton button = new JButton(text);
        button.addActionListener(e -> {
            wasCancelled = false;
            action.run();
            closeParent(button);
        });
        buttons.add(button);
        if (isDefault) this.defaultButton = button;
        return this;
    }

    private void closeParent(JButton source) {
        JDialog dialog = (JDialog) SwingUtilities.getWindowAncestor(source);
        if (dialog != null) {
            dialog.dispose();
        }
    }

    /** Shows the dialog without a parent and returns whether the user confirmed. */
    public boolean showAndGet() {
        return showAndGet(null);
    }

    /** Shows the dialog centered on {@code parent} and returns whether the user confirmed. */
    public boolean showAndGet(JFrame parent) {
        JDialog dialog = new JDialog(parent, title, true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getRootPane().registerKeyboardAction(
                e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel panel = new JPanel(new BorderLayout(JdfScale.scale(10), JdfScale.scale(10)));
        panel.setBorder(JdfUi.empty(12));

        if (centerComponent != null) {
            panel.add(centerComponent, BorderLayout.CENTER);
        } else if (message != null) {
            JLabel label = new JLabel("<html><body style='width: 300px;'>" + message + "</body></html>");
            label.setFont(JdfFonts.label(13f));
            panel.add(label, BorderLayout.CENTER);
        }

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, JdfScale.scale(8), 0));
        for (int i = buttons.size() - 1; i >= 0; i--) {
            // Add in reverse so that Cancel sits left of OK.
            buttonPanel.add(buttons.get(i));
        }
        panel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        if (preferredSize != null) {
            dialog.setSize(preferredSize);
        } else {
            dialog.pack();
        }
        dialog.setLocationRelativeTo(parent);

        // The default button answers Enter.
        if (defaultButton != null) {
            dialog.getRootPane().setDefaultButton(defaultButton);
        }

        dialog.setVisible(true);
        return result;
    }

    public boolean wasCancelled() { return wasCancelled; }
}
