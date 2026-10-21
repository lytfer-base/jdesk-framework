package com.jdesk.frame.ui;

import javax.swing.Icon;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Text, icon, tooltip and click handler of a simple status bar widget; a complex widget implements
 * {@link JdfStatusBarWidget#createComponent(JdfStatusBar)} instead.
 */
public final class JdfStatusBarWidgetPresentation {

    private final String text;
    private final Icon icon;
    private final String tooltip;
    private final Consumer<MouseEvent> clickHandler;

    private JdfStatusBarWidgetPresentation(Builder builder) {
        this.text = builder.text;
        this.icon = builder.icon;
        this.tooltip = builder.tooltip;
        this.clickHandler = builder.clickHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getText() {
        return text;
    }

    public Icon getIcon() {
        return icon;
    }

    public String getTooltip() {
        return tooltip;
    }

    public Consumer<MouseEvent> getClickHandler() {
        return clickHandler;
    }

    public static final class Builder {

        private String text;
        private Icon icon;
        private String tooltip;
        private Consumer<MouseEvent> clickHandler;

        private Builder() {
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder icon(Icon icon) {
            this.icon = icon;
            return this;
        }

        public Builder tooltip(String tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder onClick(Consumer<MouseEvent> clickHandler) {
            this.clickHandler = clickHandler;
            return this;
        }

        public JdfStatusBarWidgetPresentation build() {
            return new JdfStatusBarWidgetPresentation(this);
        }
    }
}
