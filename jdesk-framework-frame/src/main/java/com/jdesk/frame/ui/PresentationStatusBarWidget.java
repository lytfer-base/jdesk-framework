package com.jdesk.frame.ui;

import java.util.Objects;

/** Simple status bar widget driven by a {@link JdfStatusBarWidgetPresentation}. */
public final class PresentationStatusBarWidget implements JdfStatusBarWidget {

    private final String id;
    private final JdfStatusBar.Position position;
    private final int order;
    private final JdfStatusBarWidgetPresentation presentation;

    public PresentationStatusBarWidget(
            String id,
            JdfStatusBar.Position position,
            int order,
            JdfStatusBarWidgetPresentation presentation) {
        this.id = Objects.requireNonNull(id, "id");
        this.position = position == null ? JdfStatusBar.Position.RIGHT : position;
        this.order = order;
        this.presentation = Objects.requireNonNull(presentation, "presentation");
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public JdfStatusBar.Position getPosition() {
        return position;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public JdfStatusBarWidgetPresentation getPresentation() {
        return presentation;
    }
}
