package com.jdesk.frame.ui;

/**
 * Options of a custom window header. The main window keeps the default product icon and short title; standalone
 * windows can add a module mark, a subtitle and a taller header.
 */
public final class JdfHeaderOptions {

    private final String title;
    private final String subtitle;
    private final String moduleMarkText;
    private final boolean showProductIcon;
    private final int height;

    private JdfHeaderOptions(Builder builder) {
        this.title = normalize(builder.title);
        this.subtitle = normalize(builder.subtitle);
        this.moduleMarkText = normalize(builder.moduleMarkText);
        this.showProductIcon = builder.showProductIcon;
        if (builder.height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }
        this.height = builder.height;
    }

    public static JdfHeaderOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String title() {
        return title;
    }

    public String subtitle() {
        return subtitle;
    }

    public String moduleMarkText() {
        return moduleMarkText;
    }

    public boolean showProductIcon() {
        return showProductIcon;
    }

    public int height() {
        return height;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Builder {
        private String title = "";
        private String subtitle = "";
        private String moduleMarkText = "";
        private boolean showProductIcon = true;
        private int height = JdfHeader.DEFAULT_HEIGHT;

        private Builder() {
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder subtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder moduleMarkText(String moduleMarkText) {
            this.moduleMarkText = moduleMarkText;
            return this;
        }

        public Builder showProductIcon(boolean showProductIcon) {
            this.showProductIcon = showProductIcon;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public JdfHeaderOptions build() {
            return new JdfHeaderOptions(this);
        }
    }
}
