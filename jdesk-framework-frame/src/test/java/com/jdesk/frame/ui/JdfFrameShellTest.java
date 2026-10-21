package com.jdesk.frame.ui;

import com.jdesk.core.app.Application;
import com.jdesk.core.app.ApplicationLifecycleListener;
import com.jdesk.core.app.ApplicationTaskScope;
import com.jdesk.core.extensions.ExtensionRegistry;
import com.jdesk.core.message.MessageBus;
import com.jdesk.core.service.ServiceContainer;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class JdfFrameShellTest {

    @Test
    void installsRootPaneAndHeaderOnFrame() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        JdfWindowFrame frame = new JdfWindowFrame("示例应用 - 记录");
        JdfHeaderOptions options = JdfHeaderOptions.builder()
                .title("记录")
                .subtitle("多标签自动保存编辑器")
                .moduleMarkText("R")
                .showProductIcon(false)
                .height(58)
                .build();

        JdfFrameShell shell = JdfFrameShell.install(frame, new FakeApplication(), options);

        assertInstanceOf(JdfRootPane.class, frame.getRootPane());
        assertSame(frame.getRootPane(), shell.rootPane());
        assertSame(shell.header(), shell.rootPane().getHeader());
        assertEquals("记录", shell.header().getTitleText());
        assertEquals("多标签自动保存编辑器", shell.header().getSubtitleText());
        assertEquals(58, shell.header().getHeaderHeight());
        frame.dispose();
    }

    private static final class FakeApplication implements Application {
        @Override
        public void init() {
        }

        @Override
        public void start() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void addLifecycleListener(ApplicationLifecycleListener listener) {
        }

        @Override
        public void removeLifecycleListener(ApplicationLifecycleListener listener) {
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public State getState() {
            return State.RUNNING;
        }

        @Override
        public ClassLoader getApplicationClassLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public ApplicationTaskScope getTaskScope() {
            return null;
        }

        @Override
        public ServiceContainer getServiceContainer() {
            return null;
        }

        @Override
        public <T> T getService(Class<T> serviceClass) {
            throw new IllegalStateException("No fake service registered: " + serviceClass.getName());
        }

        @Override
        public ExtensionRegistry getExtensionRegistry() {
            return null;
        }

        @Override
        public MessageBus getMessageBus() {
            return null;
        }
    }
}
