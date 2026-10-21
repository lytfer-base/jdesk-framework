package com.jdesk.frame.action;

import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.KeyStroke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AnActionPresentationTest {

    @Test
    void dynamicPresentationUpdatesSwingActionValues() {
        KeyStroke originalShortcut = KeyStroke.getKeyStroke("control I");
        KeyStroke updatedShortcut = KeyStroke.getKeyStroke("control U");
        AnAction action = new AnAction("test.action", "Initial", "Original", null, originalShortcut) {
            @Override
            public void actionPerformed(ActionContext context) {
            }

            @Override
            public void update(ActionContext context, ActionPresentation presentation) {
                presentation.setText("Updated");
                presentation.setDescription("Updated description");
                presentation.setShortcut(updatedShortcut);
                presentation.setEnabled(false);
                presentation.setVisible(false);
            }
        };

        Action swingAction = action.toSwingAction(new ActionContext(null, null, null));

        assertFalse(swingAction.isEnabled());
        assertEquals("Updated", swingAction.getValue(Action.NAME));
        assertEquals("Updated description", swingAction.getValue(Action.SHORT_DESCRIPTION));
        assertEquals(updatedShortcut, swingAction.getValue(Action.ACCELERATOR_KEY));
        assertEquals(Boolean.FALSE, swingAction.getValue(ActionPresentation.VISIBLE_KEY));
    }
}
