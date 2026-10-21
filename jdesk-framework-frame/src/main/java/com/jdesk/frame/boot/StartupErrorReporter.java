package com.jdesk.frame.boot;

import com.jdesk.core.i18n.I18n;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;

final class StartupErrorReporter {

    private static final String TITLE = "Application Startup Failed";
    private static final String MESSAGE = "The application failed to start.";

    private StartupErrorReporter() {
    }

    static void showError(Throwable failure) {
        System.err.println();
        System.err.println("**" + TITLE + "**");
        System.err.println();
        System.err.println(buildMessage(MESSAGE, failure));

        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {
        }

        try {
            JOptionPane.showMessageDialog(
                    JOptionPane.getRootFrame(),
                    prepareMessage(buildMessage(localized("frame.startup.failedMessage", MESSAGE), failure)),
                    localized("frame.startup.failedTitle", TITLE),
                    JOptionPane.ERROR_MESSAGE);
        } catch (Throwable dialogFailure) {
            System.err.println();
            System.err.println("Failed to show startup error dialog:");
            dialogFailure.printStackTrace(System.err);
        }
    }

    /** Don't use {@code I18n.message}: startup may fail before the strings are registered, when it returns the key. */
    private static String localized(String key, String english) {
        return I18n.find(key).orElse(english);
    }

    private static String buildMessage(String lead, Throwable failure) {
        StringWriter message = new StringWriter();
        message.append(lead);
        message.append(System.lineSeparator()).append(System.lineSeparator());
        failure.printStackTrace(new PrintWriter(message));
        return message.toString();
    }

    private static JScrollPane prepareMessage(String message) {
        JTextPane textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setText(message.replace("\t", "    "));
        textPane.setBackground(UIManager.getColor("Panel.background"));
        textPane.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(
                textPane,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setBorder(null);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Dimension preferredSize = scrollPane.getPreferredSize();
        int maxWidth = screenSize.width / 2;
        int maxHeight = screenSize.height / 2;
        if (preferredSize.width > maxWidth || preferredSize.height > maxHeight) {
            scrollPane.setPreferredSize(new Dimension(
                    Math.min(preferredSize.width, maxWidth),
                    Math.min(preferredSize.height, maxHeight)));
        }
        return scrollPane;
    }
}
