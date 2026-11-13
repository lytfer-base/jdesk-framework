package com.jdesk.frame.boot;

import com.jdesk.core.app.*;
import com.jdesk.core.disposer.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the public shutdown entry without opening a window. */
@Timeout(20)
class JdeskApplicationShutdownTest {
    @TempDir Path temporary;
    @AfterEach void reset(){Application app=ApplicationManager.getApplicationIfCreated();if(app!=null)app.shutdown();Disposer.clearAll();PathManager.resetForTests();}
    @Test void edtReturnsWhileTheNonDaemonExitThreadFinishesTheActualResources()throws Exception {
        PathManager.setHomePathForTests(temporary);var app=new JdeskApplication();app.init();var bus=app.getMessageBus();
        var resource=new HeldResource();app.getServiceContainer().registerInstance(HeldResource.class,resource);var returned=new CompletableFuture<Void>();
        boolean returnedWhileHeld;
        try{
            SwingUtilities.invokeLater(()->{app.shutdown();returned.complete(null);});assertTrue(resource.entered.await(3,TimeUnit.SECONDS));
            try{returned.get(2,TimeUnit.SECONDS);returnedWhileHeld=true;}catch(TimeoutException blocked){returnedWhileHeld=false;}
            assertEquals(Application.State.SHUTTING_DOWN,app.getState());assertSame(app,ApplicationManager.getApplicationIfCreated());assertFalse(bus.isDisposed());
            CompletableFuture.runAsync(app::shutdown).get(2,TimeUnit.SECONDS);assertEquals(1,resource.calls.get());
        }finally{resource.release.countDown();returned.get(5,TimeUnit.SECONDS);if(resource.thread!=null&&!resource.edt)resource.thread.join(5000);}
        assertTrue(returnedWhileHeld,"The public EDT call waited for resource disposal");assertFalse(resource.edt);assertFalse(resource.daemon);
        assertTrue(resource.finished);assertEquals(Application.State.DISPOSED,app.getState());assertNull(ApplicationManager.getApplicationIfCreated());assertTrue(bus.isDisposed());
    }
    @Test void aVetoCanBeFollowedByOneExitWithoutReplayingPreparation()throws Exception {
        PathManager.setHomePathForTests(temporary);var app=new JdeskApplication();app.init();var resource=new HeldResource();
        app.getServiceContainer().registerInstance(HeldResource.class,resource);var allow=new AtomicBoolean();var events=new CopyOnWriteArrayList<String>();
        app.addLifecycleListener(new ApplicationLifecycleListener(){
            private void record(String phase){assertTrue(SwingUtilities.isEventDispatchThread());events.add(phase);app.shutdown();}
            public void appClosing(Application app){record("closing");}
            public boolean canExitApplication(Application app){record("veto");return allow.get();}
            public void onApplicationSaving(Application app){record("saving");}
            public void appWillBeClosed(Application app){record("will-close");}
            public void onApplicationShuttingDown(Application app){record("shutting-down");}
        });
        SwingUtilities.invokeAndWait(app::shutdown);assertEquals(List.of("closing","veto"),events);assertEquals(0,resource.calls.get());
        allow.set(true);var returned=new CompletableFuture<Void>();boolean returnedWhileHeld;
        try{
            SwingUtilities.invokeLater(()->{app.shutdown();returned.complete(null);});assertTrue(resource.entered.await(3,TimeUnit.SECONDS));
            try{returned.get(2,TimeUnit.SECONDS);returnedWhileHeld=true;}catch(TimeoutException blocked){returnedWhileHeld=false;}
            CompletableFuture.runAsync(app::shutdown).get(2,TimeUnit.SECONDS);
        }finally{resource.release.countDown();returned.get(5,TimeUnit.SECONDS);if(resource.thread!=null&&!resource.edt)resource.thread.join(5000);}
        assertTrue(returnedWhileHeld);assertEquals(List.of("closing","veto","closing","veto","saving","will-close","shutting-down"),events);
        assertEquals(1,resource.calls.get());assertEquals(Application.State.DISPOSED,app.getState());assertNull(ApplicationManager.getApplicationIfCreated());
    }
    static final class HeldResource implements Disposable {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();
        volatile Thread thread;volatile boolean edt,daemon,finished;
        public void dispose(){thread=Thread.currentThread();edt=SwingUtilities.isEventDispatchThread();daemon=thread.isDaemon();calls.incrementAndGet();entered.countDown();
            try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{finished=true;}}
    }
}
