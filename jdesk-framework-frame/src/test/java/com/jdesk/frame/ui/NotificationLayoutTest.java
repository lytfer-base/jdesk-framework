package com.jdesk.frame.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class NotificationLayoutTest {

    @Test
    void textIsEscaped() {
        assertEquals("a &lt;b&gt; &amp; c", Notification.escapeHtml("a <b> & c"));
    }

    @Test
    void ampersandIsEscapedOnlyOnce() {
        assertEquals("&amp;lt;", Notification.escapeHtml("&lt;"));
    }

    @Test
    void missingTextEscapesToEmpty() {
        assertEquals("", Notification.escapeHtml(null));
    }

    @Test
    void ownerlessBalloonSitsAtBottomRightOfWorkArea() {
        assertEquals(new Point(1920 - 300 - 12, 1040 - 120 - 12),
                Notification.bottomRight(new Rectangle(0, 0, 1920, 1040), new Dimension(300, 120), 12));
    }

    @Test
    void bottomRightFollowsAWorkAreaThatDoesNotStartAtTheOrigin() {
        Dimension size = new Dimension(300, 120);

        assertEquals(new Point(1920 - 300 - 12, 40 + 1040 - 120 - 12),
                Notification.bottomRight(new Rectangle(0, 40, 1920, 1040), size, 12));
        assertEquals(new Point(48 + 1872 - 300 - 12, 1080 - 120 - 12),
                Notification.bottomRight(new Rectangle(48, 0, 1872, 1080), size, 12));
    }

    @Test
    void titleContentAndLinkTextAreEscapedInTheBalloon() {
        JPanel content = Notification.warning("a <b>", "x & y").withLink("1 < 2", () -> { }).buildContent(() -> { });

        List<String> texts = all(content, JLabel.class).stream().map(JLabel::getText).toList();

        assertTrue(texts.stream().anyMatch(text -> text.contains("a &lt;b&gt;")), texts::toString);
        assertTrue(texts.stream().anyMatch(text -> text.contains("x &amp; y")), texts::toString);
        assertTrue(texts.stream().anyMatch(text -> text.contains("1 &lt; 2")), texts::toString);
        assertTrue(texts.stream().noneMatch(text -> text.contains("a <b>") || text.contains("x & y") || text.contains("1 < 2")),
                texts::toString);
    }

    @Test
    void clickingTheLinkRunsTheCallbackAndKeepsTheBalloonOpen() {
        AtomicInteger linkClicks = new AtomicInteger();
        AtomicInteger dismissals = new AtomicInteger();
        JPanel content = Notification.info("Title", "Body")
                .withLink("Details", linkClicks::incrementAndGet)
                .buildContent(dismissals::incrementAndGet);

        click(labelContaining(content, "Details"));

        assertEquals(1, linkClicks.get());
        assertEquals(0, dismissals.get());
    }

    @Test
    void clickingTheBalloonDismissesItWithoutFollowingTheLink() {
        AtomicInteger linkClicks = new AtomicInteger();
        AtomicInteger dismissals = new AtomicInteger();
        JPanel content = Notification.info("Title", "Body")
                .withLink("Details", linkClicks::incrementAndGet)
                .buildContent(dismissals::incrementAndGet);

        click(content);

        assertEquals(1, dismissals.get());
        assertEquals(0, linkClicks.get());
    }

    @Test
    void linkIsShownWithoutContent() {
        JPanel content = Notification.info("Title", null).withLink("Details", () -> { }).buildContent(() -> { });

        labelContaining(content, "Details");
    }

    @Test
    void laterLinkReplacesTheEarlierOne() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        JPanel content = Notification.info("Title", "Body")
                .withLink("First", first::incrementAndGet)
                .withLink("Second", second::incrementAndGet)
                .buildContent(() -> { });

        assertTrue(all(content, JLabel.class).stream().noneMatch(label -> label.getText().contains("First")));
        click(labelContaining(content, "Second"));

        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void actionButtonRunsItsCallbackAndDismisses() {
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger dismissals = new AtomicInteger();
        JPanel content = Notification.info("Title", "Body")
                .withAction("Go", runs::incrementAndGet)
                .buildContent(dismissals::incrementAndGet);

        all(content, JButton.class).get(0).doClick(0);

        assertEquals(1, runs.get());
        assertEquals(1, dismissals.get());
    }

    @Test
    void inARealWindowClickingTheLinkRunsOnlyTheCallback() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Clicks clicks = clickInRealWindow(label -> label.getWidth() / 2, "Details");

        assertEquals(1, clicks.link.get());
        assertEquals(0, clicks.dismissals.get());
    }

    @Test
    void inARealWindowClickingNextToTheLinkDismissesTheBalloon() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        // The link row is wider than its text: the empty part must not follow the link.
        Clicks clicks = clickInRealWindow(label -> label.getParent().getWidth() - label.getX() - 3, "Details");

        assertEquals(0, clicks.link.get());
        assertEquals(1, clicks.dismissals.get());
    }

    @Test
    void inARealWindowClickingTheBodyTextDismissesTheBalloon() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Clicks clicks = clickInRealWindow(label -> label.getWidth() / 2, "Body");

        assertEquals(0, clicks.link.get());
        assertEquals(1, clicks.dismissals.get());
    }

    private record Clicks(AtomicInteger link, AtomicInteger dismissals) { }

    private static Clicks clickInRealWindow(ToIntFunction<JLabel> x, String text) throws Exception {
        Clicks clicks = new Clicks(new AtomicInteger(), new AtomicInteger());
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = Notification.info("Title", "Body")
                    .withLink("Details", clicks.link::incrementAndGet)
                    .buildContent(clicks.dismissals::incrementAndGet);
            JDialog window = new JDialog();
            try {
                window.setUndecorated(true);
                window.setContentPane(content);
                window.pack();
                JLabel label = labelContaining(content, text);
                Point point = SwingUtilities.convertPoint(label, x.applyAsInt(label), label.getHeight() / 2, window);
                int[] ids = {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED};
                for (int id : ids) {
                    int modifiers = id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0;
                    window.dispatchEvent(new MouseEvent(window, id, System.currentTimeMillis(), modifiers,
                            point.x, point.y, 1, false, MouseEvent.BUTTON1));
                }
            } finally {
                window.dispose();
            }
        });
        return clicks;
    }

    private static void click(Component target) {
        long now = System.currentTimeMillis();
        for (int id : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED}) {
            target.dispatchEvent(new MouseEvent(target, id, now, 0, 1, 1, 1, false));
        }
    }

    private static JLabel labelContaining(Container root, String text) {
        return all(root, JLabel.class).stream()
                .filter(label -> label.getText() != null && label.getText().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No label contains " + text));
    }

    private static <T extends Component> List<T> all(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container container) {
                found.addAll(all(container, type));
            }
        }
        return found;
    }
}
