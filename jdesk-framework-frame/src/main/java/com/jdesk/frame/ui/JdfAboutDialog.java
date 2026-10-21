package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationInfo;
import com.jdesk.core.app.ApplicationManager;
import com.jdesk.core.i18n.I18n;
import com.jdesk.core.i18n.UiLanguage;
import com.jdesk.frame.icon.JdfIconManager;
import com.jdesk.frame.laf.JdfColors;
import com.jdesk.frame.ui.components.JdfUi;
import com.jdesk.frame.ui.scale.JdfFonts;
import com.jdesk.frame.ui.scale.JdfScale;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * About dialog with the product icon, full name, description, version, release date and copyright, labeled with
 * framework strings. The description comes from the {@code app.description} string if present, the rest from
 * {@link ApplicationInfo}; unset fields are left out.
 */
public final class JdfAboutDialog {

    private static final int ICON_SIZE = 64;
    private static final int TEXT_WIDTH = 260;

    private JdfAboutDialog() {
    }

    /** Shows the modal About dialog centered on {@code parent}, titled with the full product name. */
    public static void show(JFrame parent) {
        ApplicationInfo info = ApplicationInfo.getInstance();
        DialogBuilder.create(I18n.message("frame.about.title", "product", info.getFullProductName()))
                .withCenterComponent(createContent(info))
                .withButton(I18n.message("frame.dialog.ok"), () -> {
                }, true)
                .showAndGet(parent);
    }

    private static JComponent createContent(ApplicationInfo info) {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(JdfUi.empty(20, 36, 8, 36));

        Icon icon = applicationIcon();
        if (icon != null) {
            JLabel iconLabel = new JLabel(icon);
            iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(iconLabel);
            content.add(JdfUi.verticalStrut(16));
        }

        content.add(line(info.getFullProductName(), JdfFonts.derive(JdfFonts.label(), Font.BOLD, 20f), JdfColors.text()));
        String description = I18n.find("app.description").orElse(info.getDescription());
        if (!description.isBlank()) {
            content.add(JdfUi.verticalStrut(8));
            content.add(line(wrapped(description), JdfFonts.label(12f), JdfColors.mutedText()));
        }

        content.add(JdfUi.verticalStrut(16));
        content.add(line(I18n.message("frame.about.version", "version", info.getDisplayVersion()),
                JdfFonts.label(13f), JdfColors.text()));
        if (!info.getBuildDate().isBlank()) {
            content.add(JdfUi.verticalStrut(4));
            String date = formatReleaseDate(info.getBuildDate(), I18n.current());
            content.add(line(I18n.message("frame.about.released", "date", date), JdfFonts.label(13f), JdfColors.text()));
        }

        content.add(JdfUi.verticalStrut(16));
        content.add(line("© " + info.getCompanyName(), JdfFonts.label(12f), JdfColors.mutedText()));
        return content;
    }

    private static JLabel line(String text, Font font, Color color) {
        JLabel label = new JLabel(text, JLabel.CENTER);
        label.setFont(font);
        label.setForeground(color);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    /** Wraps and centers a long description at a fixed width through Swing's HTML rendering. */
    private static String wrapped(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='text-align:center;width:" + JdfScale.scale(TEXT_WIDTH) + "px'>" + escaped + "</div></html>";
    }

    /** Formats the date in the UI language's long style, or returns it unchanged if it doesn't parse. */
    static String formatReleaseDate(String isoDate, UiLanguage language) {
        try {
            return LocalDate.parse(isoDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(language.locale()));
        } catch (RuntimeException e) {
            return isoDate;
        }
    }

    /** Gets the product icon from the global {@link JdfIconManager}; without that service, no icon is shown. */
    private static Icon applicationIcon() {
        Application application = ApplicationManager.getApplicationIfCreated();
        if (application == null || !application.getServiceContainer().hasService(JdfIconManager.class)) {
            return null;
        }
        return application.getService(JdfIconManager.class).getApplicationIcon(ICON_SIZE);
    }
}
