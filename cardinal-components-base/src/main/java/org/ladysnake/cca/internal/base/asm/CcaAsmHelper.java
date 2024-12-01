/*
 * Cardinal-Components-API
 * Copyright (C) 2019-2024 Ladysnake
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.ladysnake.cca.internal.base.asm;

import it.unimi.dsi.fastutil.objects.ReferenceArraySet;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.Component;
import org.ladysnake.cca.api.v3.component.ComponentContainer;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentProvider;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.ladysnake.cca.api.v3.component.sync.AutoSyncedComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;
import org.ladysnake.cca.internal.base.AbstractComponentContainer;
import org.ladysnake.cca.internal.base.ImmutableInternals;
import org.ladysnake.cca.internal.base.QualifiedComponentFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class CcaAsmHelper {

    /**
     * If {@code true}, any class generated through {@link #generateClass(ClassWriter, String, boolean, Object)} will
     * be checked and written to disk. Highly recommended when editing methods in this class.
     */
    public static final boolean DEBUG_CLASSES = Boolean.getBoolean("cca.debug.asm");
    public static final int ASM_VERSION = Opcodes.ASM9;
    // existing references
    public static final String COMPONENT = Type.getInternalName(Component.class);
    public static final String COMPONENT_CONTAINER = Type.getInternalName(ComponentContainer.class);
    public static final String COMPONENT_TYPE = Type.getInternalName(ComponentKey.class);
    public static final String IMMUTABLE_COMPONENT_TYPE = Type.getInternalName(ImmutableComponentKey.class);
    public static final String IMMUTABLE_COMPONENT_WRAPPER = Type.getInternalName(ImmutableComponentWrapper.class);
    public static final String IMMUTABLE_INTERNALS = Type.getInternalName(ImmutableInternals.class);
    public static final String AUTO_SYNCED_COMPONENT = Type.getInternalName(AutoSyncedComponent.class);
    public static final String SERVER_TICKING_COMPONENT = Type.getInternalName(ServerTickingComponent.class);
    public static final String CLIENT_TICKING_COMPONENT = Type.getInternalName(ClientTickingComponent.class);
    public static final String DYNAMIC_COMPONENT_CONTAINER_IMPL = Type.getInternalName(AbstractComponentContainer.class);
    public static final String IDENTIFIER = FabricLoader.getInstance().getMappingResolver().mapClassName("intermediary", "net.minecraft.class_2960").replace('.', '/');
    public static final String EVENT = Type.getInternalName(Event.class);
    public static final MethodType MODIFIER_MODIFY_TYPE = MethodType.methodType(void.class, ImmutableComponent.class, Object.class);
    // generated references
    public static final String STATIC_COMPONENT_CONTAINER = createClassName("GeneratedComponentContainer");
    public static final String STATIC_CONTAINER_GETTER_DESC = "()L" + COMPONENT + ";";
    public static final String STATIC_COMPONENT_TYPE = createClassName("ComponentType");
    public static final String STATIC_IMMUTABLE_COMPONENT_TYPE = createClassName("ImmutableComponentType");
    public static final String STATIC_CONTAINER_FACTORY = createClassName("GeneratedContainerFactory");
    public static final String STATIC_IMMUTABLE_COMPONENT_WRAPPER = createClassName("GeneratedImmutableComponentWrapper");
    public static final String ABSTRACT_COMPONENT_CONTAINER_CTOR_DESC;

    private static final List<AsmGeneratedCallbackInfo> asmGeneratedCallbacks = findAsmComponentCallbacks();

    record AsmGeneratedCallbackInfo(String containerCallbackName, Class<? extends Component> componentClass, String componentCallbackName) {}

    static {
        try {
            ABSTRACT_COMPONENT_CONTAINER_CTOR_DESC = Type.getConstructorDescriptor(AbstractComponentContainer.class.getConstructor());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Failed to find one or more method descriptors", e);
        }
    }

    private static List<AsmGeneratedCallbackInfo> findAsmComponentCallbacks() {
        List<AsmGeneratedCallbackInfo> asmGeneratedCallbacks = new ArrayList<>();
        for (Method containerMethod : ComponentContainer.class.getMethods()) {
            @Nullable AsmGeneratedCallback annotation = containerMethod.getAnnotation(AsmGeneratedCallback.class);
            if (annotation != null) {
                Class<? extends Component> componentClass = annotation.value();
                boolean found = false;
                for (Method componentMethod : componentClass.getDeclaredMethods()) {
                    if (componentMethod.isAnnotationPresent(CalledByAsm.class)) {
                        asmGeneratedCallbacks.add(new AsmGeneratedCallbackInfo(containerMethod.getName(), componentClass, componentMethod.getName()));
                        found = true;
                    }
                }
                if (!found) throw new IllegalStateException("No ASM-called method found in " + componentClass);
            }
        }
        return asmGeneratedCallbacks;
    }

    /**
     * {@return the internal name of a class with the given name in the same packages as {@link CcaAsmHelper}}
     */
    public static String createClassName(String name) {
        return CcaAsmHelper.class.getPackageName().replace('.', '/') + "/_generated_$" + name;
    }

    /**
     * Creates a new class in the same package as {@link CcaAsmHelper}.
     * @param classNode the class to generate
     * @param hidden whether the class should be hidden, in which case the name need not be unique
     * @param classData if the class is hidden, the data to attach to it, accessible through {@link MethodHandles#classData(MethodHandles.Lookup, String, Class)}
     * @return the generated class
     */
    public static Class<?> generateClass(ClassNode classNode, boolean hidden, @Nullable Object classData) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return generateClass(writer, classNode.name, hidden, classData);
    }

    // Only used while debugging, so that the hidden classes don't all overwrite each other
    private static final AtomicInteger nextDebugId = new AtomicInteger();
    private static Class<?> generateClass(ClassWriter classWriter, String className, boolean hidden, @Nullable Object classData) throws IOException {
        try {
            if (!hidden && classData != null) {
                throw new IllegalArgumentException("Class data is only supported for hidden classes");
            }
            byte[] bytes = classWriter.toByteArray();
            if (DEBUG_CLASSES) {
                ClassReader classReader = new ClassReader(bytes);
                classReader.accept(new CheckClassAdapter(null), 0);
                Path path = Paths.get(classReader.getClassName() + '_' + nextDebugId.getAndIncrement() + ".class");
                Files.createDirectories(path.getParent());
                Files.write(path, bytes);
            }
            if (hidden) {
                if (classData == null) {
                    return MethodHandles.lookup().defineHiddenClass(bytes, false, MethodHandles.Lookup.ClassOption.STRONG).lookupClass();
                } else {
                    return MethodHandles.lookup().defineHiddenClassWithClassData(bytes, classData, false, MethodHandles.Lookup.ClassOption.STRONG).lookupClass();
                }
            } else {
                return MethodHandles.lookup().defineClass(bytes);
            }
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            // IllegalStateException and IllegalArgumentException can be thrown by CheckClassAdapter
            throw new IOException("Failed to generate class " + className, e);
        } catch (IllegalAccessException e) {
            throw new StaticComponentLoadingException("Failed to define class " + className, e);
        }
    }

    public static String getJavaIdentifierName(Identifier identifier) {
        return identifier.toString()
            .replace(':', '$')
            .replace('/', '$')
            .replace('.', '¤')
            .replace('-', '£');
    }

    public static String getStaticStorageGetterName(Identifier identifier) {
        return "get$" + getJavaIdentifierName(identifier);
    }

    public static Method findSam(Class<?> callbackClass) {
        if (!callbackClass.isInterface()) {
            throw badFunctionalInterface(callbackClass);
        }
        Method ret = null;
        for (Method m : callbackClass.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                if (ret != null) {
                    throw badFunctionalInterface(callbackClass);
                }
                ret = m;
            }
        }
        if (ret == null) {
            throw badFunctionalInterface(callbackClass);
        }
        return ret;
    }

    private static IllegalArgumentException badFunctionalInterface(Class<?> callbackClass) {
        return new IllegalArgumentException(callbackClass + " is not a functional interface!");
    }

    /**
     * Defines an implementation of {@link ComponentContainer} that supports direct component access.
     *
     * <p>Instances of the returned class can be returned by {@link ComponentProvider#getComponentContainer()}.
     * <strong>This method must not be called before the static component container interface has been defined!</strong>
     *
     * @param componentFactoryType the interface implemented by the component factories used to initialize this container
     * @param componentFactories   a map of {@link ComponentKey}s to factories for components of that type
     * @param componentImpls       a map of {@link ComponentKey}s to their actual implementation classes for the container
     * @return the generated container class
     * @deprecated cannot remove in 1.17 because internal compatibility
     */
    @Deprecated(forRemoval = true)
    public static <I> Class<? extends ComponentContainer> spinComponentContainer(Class<? super I> componentFactoryType, Map<ComponentKey<?>, I> componentFactories, Map<ComponentKey<?>, Class<? extends Component>> componentImpls) throws IOException {
        Map<ComponentKey<?>, QualifiedComponentFactory<I>> merged = new LinkedHashMap<>();
        for (var entry : componentFactories.entrySet()) {
            merged.put(entry.getKey(), new QualifiedComponentFactory<>(entry.getValue(), componentImpls.get(entry.getKey()), Set.of()));
        }
        return spinComponentContainer(componentFactoryType, merged);
    }

    /**
     * Defines an implementation of {@link ComponentContainer} that supports direct component access.
     *
     * <p>Instances of the returned class can be returned by {@link ComponentProvider#getComponentContainer()}.
     * <strong>This method must not be called before the static component container interface has been defined!</strong>
     *
     * @param componentFactoryType the interface implemented by the component factories used to initialize this container
     * @param componentFactories   a map of {@link ComponentKey} ids to factories for components of that type
     * @return the generated container class
     */
    public static <I> Class<? extends ComponentContainer> spinComponentContainer(Class<? super I> componentFactoryType, Map<ComponentKey<?>, QualifiedComponentFactory<I>> componentFactories) throws IOException {
        CcaBootstrap.INSTANCE.ensureInitialized();

        QualifiedComponentFactory.checkDependenciesSatisfied(componentFactories);
        Map<ComponentKey<?>, QualifiedComponentFactory<I>> sorted = QualifiedComponentFactory.sort(componentFactories);
        String containerImplName = STATIC_COMPONENT_CONTAINER + "Impl";
        String componentFactoryName = Type.getInternalName(componentFactoryType);
        Method sam = findSam(componentFactoryType);
        String samDescriptor = Type.getMethodDescriptor(sam);
        Class<?>[] factoryArgs = sam.getParameterTypes();
        Type[] actualCtorArgs = new Type[factoryArgs.length];

        for (int i = 0; i < factoryArgs.length; i++) {
            actualCtorArgs[i] = Type.getType(factoryArgs[i]);
        }

        String ctorDesc = Type.getMethodDescriptor(Type.VOID_TYPE, actualCtorArgs);
        ClassNode classNode = new ClassNode(ASM_VERSION);
        classNode.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
            containerImplName,
            null,
            STATIC_COMPONENT_CONTAINER,
            null
        );

        String factoryFieldDescriptor = Type.getDescriptor(componentFactoryType);

        classNode.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "componentKeys", "Ljava/util/Set;", "Ljava/util/Set<Lorg/ladysnake/cca/api/v3/component/ComponentKey<*>;>;", null);

        MethodVisitor keys = classNode.visitMethod(Opcodes.ACC_PUBLIC, "keys", "()Ljava/util/Set;", "()Ljava/util/Set<Lorg/ladysnake/cca/api/v3/component/ComponentKey<*>;>;", null);
        keys.visitFieldInsn(Opcodes.GETSTATIC, containerImplName, "componentKeys", "Ljava/util/Set;");
        keys.visitInsn(Opcodes.ARETURN);
        keys.visitEnd();

        MethodVisitor hasComponents = classNode.visitMethod(Opcodes.ACC_PUBLIC, "hasComponents", "()Z", null, null);
        hasComponents.visitCode();
        hasComponents.visitInsn(sorted.isEmpty() ? Opcodes.ICONST_0 : Opcodes.ICONST_1);
        hasComponents.visitInsn(Opcodes.IRETURN);
        hasComponents.visitEnd();

        MethodVisitor init = classNode.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, STATIC_COMPONENT_CONTAINER, "<init>", ABSTRACT_COMPONENT_CONTAINER_CTOR_DESC, false);

        Map<AsmGeneratedCallbackInfo, MethodVisitor> callbackMethods = new LinkedHashMap<>();
        for (AsmGeneratedCallbackInfo callbackInfo : asmGeneratedCallbacks) {
            MethodVisitor visitor = classNode.visitMethod(Opcodes.ACC_PUBLIC, callbackInfo.containerCallbackName(), "()V", null, null);
            visitor.visitCode();
            callbackMethods.put(callbackInfo, visitor);
        }

        for (var entry : sorted.entrySet()) {
            Identifier identifier = entry.getKey().getId();
            String componentFieldName = getJavaIdentifierName(identifier);
            Class<? extends Component> impl = entry.getValue().impl();
            String componentFieldDescriptor = Type.getDescriptor(impl);
            String factoryFieldName = getFactoryFieldName(identifier);
            /* field declaration */
            classNode.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                factoryFieldName,
                factoryFieldDescriptor,
                null,
                null
            ).visitEnd();
            classNode.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                componentFieldName,
                componentFieldDescriptor,
                null,
                null
            ).visitEnd();
            /* constructor initialization */
            init.visitFieldInsn(Opcodes.GETSTATIC, containerImplName, factoryFieldName, factoryFieldDescriptor);
            // stack: factory
            for (int i = 0; i < factoryArgs.length; i++) {
                init.visitVarInsn(Opcodes.ALOAD, i + 1);    // first arg is for the container itself
            }
            // stack: factory factoryArgs...
            // initialize the component by calling the factory
            init.visitMethodInsn(Opcodes.INVOKEINTERFACE, componentFactoryName, sam.getName(), samDescriptor, true);
            // stack: component
            init.visitLdcInsn("Component factory " + entry.getValue().factory().getClass() + " for " + identifier + " produced a null component");
            // stack: component, errorMsg
            init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            // stack: object
            init.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(impl));
            // stack: component
            init.visitVarInsn(Opcodes.ALOAD, 0);
            // stack: component <this>
            init.visitInsn(Opcodes.SWAP);
            // stack: <this> component
            // store in the field
            init.visitFieldInsn(Opcodes.PUTFIELD, containerImplName, componentFieldName, componentFieldDescriptor);
            // <empty stack>

            /* getter implementation */
            MethodVisitor getter = classNode.visitMethod(
                Opcodes.ACC_PUBLIC,
                getStaticStorageGetterName(identifier),
                STATIC_CONTAINER_GETTER_DESC,
                null,
                null
            );
            getter.visitVarInsn(Opcodes.ALOAD, 0);
            // stack: <this>
            getter.visitFieldInsn(Opcodes.GETFIELD, containerImplName, componentFieldName, componentFieldDescriptor);
            // stack: component
            getter.visitInsn(Opcodes.ARETURN);
            getter.visitEnd();

            /* no-arg callback implementations */
            for (var e : callbackMethods.entrySet()) {
                if (e.getKey().componentClass().isAssignableFrom(impl)) {
                    generateCallbackImpl(
                        containerImplName,
                        e.getValue(),
                        componentFieldName,
                        impl,
                        componentFieldDescriptor,
                        e.getKey().componentCallbackName()
                    );
                }
            }
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitEnd();

        for (var e : callbackMethods.entrySet()) {
            e.getValue().visitInsn(Opcodes.RETURN);
            e.getValue().visitEnd();
        }

        Object[] classData = new Object[sorted.size() + 1];
        classData[0] = Collections.unmodifiableSet(new ReferenceArraySet<>(sorted.keySet()));
        // On class init, we pull out the class data and put it in the proper fields
        MethodVisitor clinit = classNode.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        ConstantDynamic constantClassData = constantClassData(Object[].class);
        clinit.visitLdcInsn(constantClassData);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitInsn(Opcodes.ICONST_0);
        clinit.visitInsn(Opcodes.AALOAD);
        clinit.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(Set.class));
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, containerImplName, "componentKeys", Type.getDescriptor(Set.class));

        int i = 1;
        for (var entry : sorted.entrySet()) {
            classData[i] = entry.getValue().factory();
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitLdcInsn(i);
            clinit.visitInsn(Opcodes.AALOAD);
            clinit.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(componentFactoryType));
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, containerImplName, getFactoryFieldName(entry.getKey().getId()), Type.getDescriptor(componentFactoryType));
            i++;
        }
        clinit.visitInsn(Opcodes.POP);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitEnd();

        return generateClass(classNode, true, classData).asSubclass(ComponentContainer.class);
    }

    @NotNull public static ConstantDynamic constantClassData(Class<?> dataType) {
        return new ConstantDynamic(
            "_",
            Type.getDescriptor(dataType),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                Type.getInternalName(MethodHandles.class),
                "classData",
                MethodType.methodType(Object.class, MethodHandles.Lookup.class, String.class, Class.class).descriptorString(),
                false
            )
        );
    }

    private static void generateCallbackImpl(String containerImplName, MethodVisitor tick, String componentFieldName, Class<? extends Component> impl, String componentFieldDescriptor, String target) {
        tick.visitVarInsn(Opcodes.ALOAD, 0);
        // stack: <this>
        tick.visitFieldInsn(Opcodes.GETFIELD, containerImplName, componentFieldName, componentFieldDescriptor);
        // stack: component
        if (impl.isInterface()) {
            tick.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(impl), target, "()V", true);
        } else {
            tick.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(impl), target, "()V", false);
        }
    }

    private static String getFactoryFieldName(Identifier identifier) {
        return getJavaIdentifierName(identifier) + "$factory";
    }
}
