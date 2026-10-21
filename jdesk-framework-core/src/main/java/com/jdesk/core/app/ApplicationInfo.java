package com.jdesk.core.app;

import java.util.Objects;

/**
 * Application metadata loaded at startup. It has no UI types; the frame layer uses the names, the version and the
 * icon resource paths to configure windows, the title area and the about dialog.
 */
public final class ApplicationInfo {

    /** Default class path location of the descriptor. */
    public static final String DEFAULT_RESOURCE = "META-INF/application-info.xml";

    private final String id;
    private final String productName;
    private final String fullProductName;
    private final String versionName;
    private final String majorVersion;
    private final String minorVersion;
    private final String microVersion;
    private final String patchVersion;
    private final String versionSuffix;
    private final String buildNumber;
    private final String buildDate;
    private final String companyName;
    private final String companyUrl;
    private final String description;
    private final String iconSvgPath;
    private final String smallIconSvgPath;
    private final String iconPngPath;
    private final String smallIconPngPath;

    private ApplicationInfo(Builder builder) {
        this.id = clean(builder.id);
        this.productName = requireText(builder.productName, "productName");
        this.fullProductName = firstText(builder.fullProductName, this.productName);
        this.versionName = clean(builder.versionName);
        this.majorVersion = firstText(builder.majorVersion, "1");
        this.minorVersion = firstText(builder.minorVersion, "0");
        this.microVersion = clean(builder.microVersion);
        this.patchVersion = clean(builder.patchVersion);
        this.versionSuffix = clean(builder.versionSuffix);
        this.buildNumber = clean(builder.buildNumber);
        this.buildDate = resolved(builder.buildDate);
        this.companyName = firstText(builder.companyName, "JDesk");
        this.companyUrl = clean(builder.companyUrl);
        this.description = clean(builder.description);
        this.iconSvgPath = normalizeResourcePath(builder.iconSvgPath);
        this.smallIconSvgPath = normalizeResourcePath(builder.smallIconSvgPath);
        this.iconPngPath = normalizeResourcePath(builder.iconPngPath);
        this.smallIconPngPath = normalizeResourcePath(builder.smallIconPngPath);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns the info registered with the current application, or the defaults if no application exists yet. */
    public static ApplicationInfo getInstance() {
        Application application = ApplicationManager.getApplicationIfCreated();
        if (application != null && application.getServiceContainer().hasService(ApplicationInfo.class)) {
            return application.getService(ApplicationInfo.class);
        }
        return ApplicationInfoLoader.loadDefault();
    }

    public String getId() {
        return id;
    }

    public String getProductName() {
        return productName;
    }

    public String getFullProductName() {
        return fullProductName;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getMajorVersion() {
        return majorVersion;
    }

    public String getMinorVersion() {
        return minorVersion;
    }

    public String getMicroVersion() {
        return microVersion;
    }

    public String getPatchVersion() {
        return patchVersion;
    }

    public String getVersionSuffix() {
        return versionSuffix;
    }

    public String getBuildNumber() {
        return buildNumber;
    }

    /** Release date as {@code yyyy-MM-dd}; empty if unset or if the build placeholder wasn't replaced. */
    public String getBuildDate() {
        return buildDate;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getCompanyUrl() {
        return companyUrl;
    }

    public String getDescription() {
        return description;
    }

    public String getIconSvgPath() {
        return iconSvgPath;
    }

    public String getSmallIconSvgPath() {
        return smallIconSvgPath;
    }

    public String getIconPngPath() {
        return iconPngPath;
    }

    public String getSmallIconPngPath() {
        return smallIconPngPath;
    }

    /** Returns {@code versionName} if set, otherwise joins the major, minor, micro, patch and suffix fields. */
    public String getFullVersion() {
        if (!versionName.isBlank()) {
            return versionName;
        }

        StringBuilder version = new StringBuilder();
        version.append(majorVersion).append('.').append(minorVersion);
        if (!microVersion.isBlank()) {
            version.append('.').append(microVersion);
        }
        if (!patchVersion.isBlank()) {
            version.append('.').append(patchVersion);
        }
        if (!versionSuffix.isBlank()) {
            version.append(' ').append(versionSuffix);
        }
        return version.toString();
    }

    /** Returns the version for display, with the build number appended when there is one. */
    public String getDisplayVersion() {
        String version = getFullVersion();
        if (buildNumber.isBlank()) {
            return version;
        }
        return version + " (" + buildNumber + ")";
    }

    private static String requireText(String value, String name) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return cleaned;
    }

    private static String firstText(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? Objects.requireNonNull(fallback, "fallback") : cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** An unreplaced placeholder such as {@code ${build.date}} counts as unset; don't show it as data. */
    private static String resolved(String value) {
        String cleaned = clean(value);
        return cleaned.startsWith("${") ? "" : cleaned;
    }

    private static String normalizeResourcePath(String path) {
        String cleaned = clean(path).replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    /** Builds an {@link ApplicationInfo} field by field, from XML or in tests. */
    public static final class Builder {
        private String id;
        private String productName = "JDesk Application";
        private String fullProductName;
        private String versionName;
        private String majorVersion = "1";
        private String minorVersion = "0";
        private String microVersion;
        private String patchVersion;
        private String versionSuffix;
        private String buildNumber;
        private String buildDate;
        private String companyName = "JDesk";
        private String companyUrl;
        private String description;
        private String iconSvgPath;
        private String smallIconSvgPath;
        private String iconPngPath;
        private String smallIconPngPath;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder productName(String productName) {
            this.productName = productName;
            return this;
        }

        public Builder fullProductName(String fullProductName) {
            this.fullProductName = fullProductName;
            return this;
        }

        public Builder versionName(String versionName) {
            this.versionName = versionName;
            return this;
        }

        public Builder majorVersion(String majorVersion) {
            this.majorVersion = majorVersion;
            return this;
        }

        public Builder minorVersion(String minorVersion) {
            this.minorVersion = minorVersion;
            return this;
        }

        public Builder microVersion(String microVersion) {
            this.microVersion = microVersion;
            return this;
        }

        public Builder patchVersion(String patchVersion) {
            this.patchVersion = patchVersion;
            return this;
        }

        public Builder versionSuffix(String versionSuffix) {
            this.versionSuffix = versionSuffix;
            return this;
        }

        public Builder buildNumber(String buildNumber) {
            this.buildNumber = buildNumber;
            return this;
        }

        public Builder buildDate(String buildDate) {
            this.buildDate = buildDate;
            return this;
        }

        public Builder companyName(String companyName) {
            this.companyName = companyName;
            return this;
        }

        public Builder companyUrl(String companyUrl) {
            this.companyUrl = companyUrl;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder iconSvgPath(String iconSvgPath) {
            this.iconSvgPath = iconSvgPath;
            return this;
        }

        public Builder smallIconSvgPath(String smallIconSvgPath) {
            this.smallIconSvgPath = smallIconSvgPath;
            return this;
        }

        public Builder iconPngPath(String iconPngPath) {
            this.iconPngPath = iconPngPath;
            return this;
        }

        public Builder smallIconPngPath(String smallIconPngPath) {
            this.smallIconPngPath = smallIconPngPath;
            return this;
        }

        public ApplicationInfo build() {
            return new ApplicationInfo(this);
        }
    }
}
