package com.jdesk.core.extensions.impl;

import com.jdesk.core.extensions.ExtensionDescriptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExtensionPointImpl} lazy extension loading.
 *
 * <p>These tests verify the core lazy-resolution mechanism without
 * requiring the full PluginManager or Application infrastructure.
 */
@DisplayName("ExtensionPointImpl lazy loading")
class ExtensionPointImplTest {

    // ---- Test contract and implementations ----

    interface Greeter {
        String greet();
    }

    static class HelloGreeter implements Greeter {
        @Override public String greet() { return "Hello"; }
    }

    static class HolaGreeter implements Greeter {
        @Override public String greet() { return "Hola"; }
    }

    static class CiaoGreeter implements Greeter {
        @Override public String greet() { return "Ciao"; }
    }

    static class BrokenInitGreeter implements Greeter {
        private static final String GREETING = fail();

        private static String fail() {
            throw new IllegalStateException("boom: greeter class init");
        }

        @Override public String greet() { return GREETING; }
    }

    static class LabeledGreeter implements Greeter {
        private final String label;

        private LabeledGreeter(String label) {
            this.label = label;
        }

        @Override public String greet() { return label; }
    }

    /** No fields, so any two instances are equal: identity is the only way to tell them apart. */
    record SameGreeter() implements Greeter {
        @Override public String greet() { return "same"; }
    }

    // ---- Helpers ----

    private ExtensionPointImpl<Greeter> point;

    @BeforeEach
    void setUp() {
        point = new ExtensionPointImpl<>("test.greeter", Greeter.class);
    }

    private ExtensionDescriptor<Greeter> descriptorFor(Class<? extends Greeter> clazz) {
        return new ExtensionDescriptor<>(
                Greeter.class,
                clazz.getName(),
                clazz.getClassLoader(),
                null);
    }

    private ExtensionDescriptor<Greeter> labeledDescriptor(String label) {
        return new ExtensionDescriptor<>(
                Greeter.class,
                LabeledGreeter.class.getName(),
                LabeledGreeter.class.getClassLoader(),
                label) {
            @Override
            public Greeter createInstance() {
                return new LabeledGreeter(label);
            }
        };
    }

    // ================================================================
    // Lazy registration and resolution
    // ================================================================

    @Nested
    @DisplayName("registerLazy")
    class RegisterLazyTests {

        @Test
        @DisplayName("descriptor is stored lazily — class NOT loaded")
        void descriptorStoredLazily() {
            List<String> requested = new ArrayList<>();
            ClassLoader recording = new ClassLoader(ExtensionPointImplTest.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    requested.add(name);
                    return super.loadClass(name, resolve);
                }
            };
            String impl = HelloGreeter.class.getName();
            point.registerLazy(new ExtensionDescriptor<>(Greeter.class, impl, recording, null));

            assertTrue(point.hasPendingExtensions(),
                    "Should have pending extensions before resolution");
            assertEquals(1, point.getExtensionCount(),
                    "Count should include pending lazy descriptors");
            assertFalse(requested.contains(impl),
                    "Registration must not load the implementation class through the descriptor's class loader");

            List<Greeter> extensions = point.getExtensions();

            assertEquals(1, extensions.size());
            assertEquals("Hello", extensions.get(0).greet());
            assertTrue(requested.contains(impl),
                    "Resolution should load the implementation through the descriptor's class loader (probe check)");
        }

        @Test
        @DisplayName("getExtensions() transparently resolves lazy descriptors")
        void resolvesOnGetExtensions() {
            point.registerLazy(descriptorFor(HelloGreeter.class));
            point.registerLazy(descriptorFor(HolaGreeter.class));

            List<Greeter> extensions = point.getExtensions();

            assertEquals(2, extensions.size());
            assertEquals("Hello", extensions.get(0).greet());
            assertEquals("Hola", extensions.get(1).greet());
            assertFalse(point.hasPendingExtensions(),
                    "Should have no pending extensions after resolution");
        }

        @Test
        @DisplayName("getExtensionCount() includes both eager and pending lazy")
        void countIncludesPending() {
            point.registerExtension(new CiaoGreeter());              // eager
            point.registerLazy(descriptorFor(HelloGreeter.class));   // lazy

            assertEquals(2, point.getExtensionCount(),
                    "Count should be eager(1) + lazy(1) = 2");
        }

        @Test
        @DisplayName("registerLazy after resolution instantiates immediately")
        void lazyAfterResolutionIsImmediate() {
            // Triggers resolution
            point.registerLazy(descriptorFor(HelloGreeter.class));
            point.getExtensions();  // resolve
            assertFalse(point.hasPendingExtensions());

            // New lazy after resolution — instantiated eagerly
            point.registerLazy(descriptorFor(HolaGreeter.class));
            assertFalse(point.hasPendingExtensions(),
                    "Should not be pending — already resolved");
            assertEquals(2, point.getExtensions().size());
        }

        @Test
        @DisplayName("failed resolution is silently caught, other extensions survive")
        void failedResolutionIsGraceful() {
            // This class doesn't exist
            ExtensionDescriptor<Greeter> badDesc = new ExtensionDescriptor<>(
                    Greeter.class,
                    "com.jdesk.core.NonExistentClass",
                    getClass().getClassLoader(),
                    null);

            point.registerLazy(badDesc);
            point.registerLazy(descriptorFor(HelloGreeter.class));

            List<Greeter> extensions = point.getExtensions();

            assertEquals(1, extensions.size(),
                    "Only the valid extension should survive");
            assertEquals("Hello", extensions.get(0).greet());
        }

        @Test
        @DisplayName("class init error during resolution is caught too, other extensions survive")
        void classInitErrorDuringResolutionIsGraceful() {
            point.registerLazy(descriptorFor(BrokenInitGreeter.class));
            point.registerLazy(descriptorFor(HelloGreeter.class));

            List<Greeter> extensions = point.getExtensions();

            assertEquals(1, extensions.size(),
                    "Only the valid extension should survive");
            assertEquals("Hello", extensions.get(0).greet());
        }
    }

    // ================================================================
    // unregisterLazy
    // ================================================================

    @Nested
    @DisplayName("unregisterLazy")
    class UnregisterLazyTests {

        @Test
        @DisplayName("unregister before resolution removes from pending list")
        void unregisterBeforeResolution() {
            ExtensionDescriptor<Greeter> desc = descriptorFor(HelloGreeter.class);
            point.registerLazy(desc);
            assertTrue(point.hasPendingExtensions());

            point.unregisterLazy(desc);

            assertFalse(point.hasPendingExtensions(),
                    "Pending queue should be empty after unregister");
            assertTrue(point.getExtensions().isEmpty(),
                    "No extensions after unregistering the only lazy one");
        }

        @Test
        @DisplayName("unregister after resolution removes resolved instance")
        void unregisterAfterResolution() {
            ExtensionDescriptor<Greeter> desc = descriptorFor(HelloGreeter.class);
            point.registerLazy(desc);
            point.getExtensions();  // resolve
            assertFalse(point.hasPendingExtensions());
            assertEquals(1, point.getExtensions().size());

            point.unregisterLazy(desc);

            assertTrue(point.getExtensions().isEmpty(),
                    "Extension should be removed after unregister");
        }

        @Test
        @DisplayName("unregister non-existent descriptor is safe (no-op)")
        void unregisterUnknownIsSafe() {
            ExtensionDescriptor<Greeter> neverRegistered = descriptorFor(CiaoGreeter.class);
            assertDoesNotThrow(() -> point.unregisterLazy(neverRegistered));
        }

        @Test
        @DisplayName("unregister the same descriptor twice is safe")
        void unregisterTwiceIsSafe() {
            ExtensionDescriptor<Greeter> desc = descriptorFor(HelloGreeter.class);
            point.registerLazy(desc);
            point.unregisterLazy(desc);
            assertDoesNotThrow(() -> point.unregisterLazy(desc));
        }

        @Test
        @DisplayName("unregister before resolution uses descriptor identity")
        void unregisterBeforeResolutionUsesDescriptorIdentity() {
            ExtensionDescriptor<Greeter> first = labeledDescriptor("first");
            ExtensionDescriptor<Greeter> second = labeledDescriptor("second");
            point.registerLazy(first);
            point.registerLazy(second);

            point.unregisterLazy(second);

            List<Greeter> extensions = point.getExtensions();
            assertEquals(1, extensions.size());
            assertEquals("first", extensions.get(0).greet());
        }

        @Test
        @DisplayName("unregister after resolution uses descriptor identity")
        void unregisterAfterResolutionUsesDescriptorIdentity() {
            ExtensionDescriptor<Greeter> first = labeledDescriptor("first");
            ExtensionDescriptor<Greeter> second = labeledDescriptor("second");
            point.registerLazy(first);
            point.registerLazy(second);
            point.getExtensions();

            point.unregisterLazy(second);

            List<Greeter> extensions = point.getExtensions();
            assertEquals(1, extensions.size());
            assertEquals("first", extensions.get(0).greet());
        }

        @Test
        @DisplayName("unregister after resolution removes the descriptor's own instance, not an equal one")
        void unregisterAfterResolutionIgnoresInstanceEquals() {
            ExtensionDescriptor<Greeter> first = descriptorFor(SameGreeter.class);
            ExtensionDescriptor<Greeter> second = descriptorFor(SameGreeter.class);
            point.registerLazy(first);
            point.registerLazy(second);
            Greeter firstInstance = point.getExtensions().get(0);

            point.unregisterLazy(second);

            List<Greeter> extensions = point.getExtensions();
            assertEquals(1, extensions.size());
            assertSame(firstInstance, extensions.get(0));
        }
    }

    // ================================================================
    // Eager + lazy coexistence
    // ================================================================

    @Nested
    @DisplayName("eager and lazy coexistence")
    class MixedRegistrationTests {

        @Test
        @DisplayName("eager extensions appear before resolved lazy extensions")
        void eagerBeforeLazy() {
            point.registerExtension(new CiaoGreeter());              // eager
            point.registerLazy(descriptorFor(HelloGreeter.class));   // lazy
            point.registerLazy(descriptorFor(HolaGreeter.class));    // lazy
            point.registerExtension(new Greeter() {                  // eager
                @Override public String greet() { return "Bonjour"; }
            });

            List<Greeter> extensions = point.getExtensions();

            assertEquals(4, extensions.size());
            // Registration order preserved: eager first, then lazies resolved in order
            assertEquals("Ciao", extensions.get(0).greet());
            assertEquals("Bonjour", extensions.get(3).greet());
            // Lazies resolved in registration order
            assertEquals("Hello", extensions.get(1).greet());
            assertEquals("Hola", extensions.get(2).greet());
        }

        @Test
        @DisplayName("unregisterExtension still works for eager instances")
        void unregisterEager() {
            CiaoGreeter ciao = new CiaoGreeter();
            point.registerExtension(ciao);
            assertEquals(1, point.getExtensions().size());

            point.unregisterExtension(ciao);
            assertTrue(point.getExtensions().isEmpty());
        }

        @Test
        @DisplayName("unregisterExtension removes by identity, not equals")
        void unregisterEagerUsesIdentity() {
            SameGreeter a = new SameGreeter();
            SameGreeter b = new SameGreeter();
            point.registerExtension(a);
            point.registerExtension(b);

            point.unregisterExtension(b);
            assertEquals(1, point.getExtensions().size());
            assertSame(a, point.getExtensions().get(0));

            point.unregisterExtension(new SameGreeter());
            assertEquals(1, point.getExtensions().size(),
                    "An equal copy that was never registered must not remove anything");
        }
    }

    // ================================================================
    // Lifecycle
    // ================================================================

    @Nested
    @DisplayName("lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("disposed point returns empty list")
        void disposedReturnsEmpty() {
            point.registerLazy(descriptorFor(HelloGreeter.class));
            point.registerExtension(new CiaoGreeter());

            point.dispose();

            assertTrue(point.getExtensions().isEmpty(),
                    "Disposed point should return empty list");
            assertEquals(0, point.getExtensionCount());
        }

        @Test
        @DisplayName("operations on disposed point throw")
        void disposedThrows() {
            point.dispose();

            assertThrows(IllegalStateException.class,
                    () -> point.registerExtension(new HelloGreeter()));
            assertThrows(IllegalStateException.class,
                    () -> point.registerLazy(descriptorFor(HelloGreeter.class)));
            assertThrows(IllegalStateException.class,
                    () -> point.unregisterLazy(descriptorFor(HelloGreeter.class)));
        }

        @Test
        @DisplayName("resolveLazy is idempotent — repeated calls are safe")
        void resolveLazyIsIdempotent() {
            point.registerLazy(descriptorFor(HelloGreeter.class));

            point.resolveLazy();
            point.resolveLazy();  // second call should be no-op
            point.resolveLazy();  // third call should be no-op

            assertEquals(1, point.getExtensions().size());
        }
    }

    // ================================================================
    // ExtensionDescriptor.createInstance
    // ================================================================

    @Nested
    @DisplayName("ExtensionDescriptor.createInstance")
    class DescriptorCreateInstanceTests {

        @Test
        @DisplayName("createInstance loads and instantiates the class")
        void createsInstance() {
            ExtensionDescriptor<Greeter> desc = descriptorFor(HelloGreeter.class);
            Greeter instance = desc.createInstance();

            assertNotNull(instance);
            assertEquals("Hello", instance.greet());
            assertEquals(HelloGreeter.class, instance.getClass());
        }

        @Test
        @DisplayName("createInstance for missing class throws RuntimeException")
        void missingClassThrows() {
            ExtensionDescriptor<Greeter> desc = new ExtensionDescriptor<>(
                    Greeter.class,
                    "com.jdesk.core.NoSuchClass",
                    getClass().getClassLoader(),
                    null);

            assertThrows(RuntimeException.class, desc::createInstance);
        }

        @Test
        @DisplayName("descriptor identity is not reduced to class name")
        void identityIsNotReducedToClassName() {
            ExtensionDescriptor<Greeter> a = descriptorFor(HelloGreeter.class);
            ExtensionDescriptor<Greeter> b = new ExtensionDescriptor<>(
                    Greeter.class,
                    HelloGreeter.class.getName(),
                    null,   // different classloader
                    "id1"); // different id

            assertNotEquals(a, b, "Descriptors with same class name but different identity must stay distinct");
        }
    }
}
