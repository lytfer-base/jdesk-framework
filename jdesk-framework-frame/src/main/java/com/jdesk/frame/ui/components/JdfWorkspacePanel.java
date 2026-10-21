package com.jdesk.frame.ui.components;

import java.awt.BorderLayout;

/** Default central workspace container of a {@link com.jdesk.frame.ui.MainFrame}. */
public class JdfWorkspacePanel extends JdfPanel {

    public JdfWorkspacePanel() {
        super(new BorderLayout(), Surface.SURFACE);
    }
}
