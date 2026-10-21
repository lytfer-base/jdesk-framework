package com.jdesk.core.extensions;

/**
 * Lazy extension descriptor holding an implementation class name and a class loader; nothing loads until
 * {@link #createInstance()}, as with {@link com.jdesk.core.service.ServiceDescriptor}. Plugins can declare many
 * extensions without loading their classes at startup.
 */
public class ExtensionDescriptor<T> {

    private final Class<T> contractClass;
    private final String implementationClassName;
    private final ClassLoader classLoader;
    private final String id;

    public ExtensionDescriptor(Class<T> contractClass,
                               String implementationClassName,
                               ClassLoader classLoader,
                               String id) {
        this.contractClass = contractClass;
        this.implementationClassName = implementationClassName;
        this.classLoader = classLoader;
        this.id = id;
    }

    public Class<T> getContractClass() {
        return contractClass;
    }

    public String getImplementationClassName() {
        return implementationClassName;
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public String getId() {
        return id;
    }

    /**
     * Loads the implementation class and creates a new instance through its no-argument constructor; the extension
     * point usually calls it once per descriptor and caches the result.
     *
     * @throws RuntimeException if the class is missing or can't be instantiated
     */
    public T createInstance() {
        try {
            ClassLoader loader = classLoader != null
                    ? classLoader
                    : getClass().getClassLoader();
            Class<?> rawClass = Class.forName(implementationClassName, true, loader);
            Class<? extends T> implClass = rawClass.asSubclass(contractClass);

            var constructor = implClass.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "Extension implementation class not found: " + implementationClassName
                    + " (contract: " + contractClass.getName() + ")", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to instantiate extension " + implementationClassName
                    + " (contract: " + contractClass.getName() + ")", e);
        }
    }

    @Override
    public String toString() {
        return "ExtDesc{" + implementationClassName
                + (id != null ? " id=" + id : "")
                + " → " + contractClass.getSimpleName() + "}";
    }
}
