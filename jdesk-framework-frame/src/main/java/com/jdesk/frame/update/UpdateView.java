package com.jdesk.frame.update;

/**
 * What the update service shows in the application, already localized by the service: the menu entry, the settings
 * section with its auto-download switch (label, hint, state) and check button, a status line and the channel's name.
 */
public record UpdateView(String menuLabel, String sectionTitle, String autoDownloadLabel, String autoDownloadHint,
                         boolean autoDownload, String checkNowLabel, String statusLine, String channelLabel) {
}
