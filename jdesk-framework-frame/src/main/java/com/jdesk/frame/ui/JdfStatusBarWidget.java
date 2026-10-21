package com.jdesk.frame.ui;

import com.jdesk.frame.ui.components.JdfStatusLabel;

import javax.swing.JComponent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Status bar widget: it returns either its own Swing component or just a {@link JdfStatusBarWidgetPresentation},
 * from which the default implementation builds a simple text and icon label.
 */
public interface JdfStatusBarWidget {

    String getId();

    default JdfStatusBar.Position getPosition() {
        return JdfStatusBar.Position.RIGHT;
    }

    default int getOrder() {
        return 0;
    }

    default JdfStatusBarWidgetPresentation getPresentation() {
        return null;
    }

    default JComponent createComponent(JdfStatusBar statusBar) {
        JdfStatusBarWidgetPresentation presentation = getPresentation();
        if (presentation == null) {
            return new JdfStatusLabel("");
        }

        JdfStatusLabel label = new JdfStatusLabel(presentation.getText() == null ? "" : presentation.getText());
        label.setIcon(presentation.getIcon());
        label.setToolTipText(presentation.getTooltip());
        if (presentation.getClickHandler() != null) {
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    presentation.getClickHandler().accept(e);
                }
            });
        }
        return label;
    }
}
