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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.mojang.serialization.MapCodec;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentProvider;
import org.ladysnake.cca.api.v3.component.StaticComponentInitializer;
import org.ladysnake.cca.internal.base.LazyDispatcher;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class CcaBootstrap extends LazyDispatcher {
    public static final String COMPONENT_TYPE_INIT_DESC = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getObjectType(CcaAsmHelper.IDENTIFIER), Type.getType(Class.class));
    public static final String IMMUTABLE_COMPONENT_TYPE_INIT_DESC = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getObjectType(CcaAsmHelper.IDENTIFIER), Type.getType(Class.class), Type.getType(Class.class), Type.getType(MapCodec.class), Type.getType(PacketCodec.class));
    public static final String COMPONENT_TYPE_GET0_DESC = "(L" + CcaAsmHelper.COMPONENT_CONTAINER + ";)L" + CcaAsmHelper.COMPONENT + ";";
    public static final String STATIC_INIT_ENTRYPOINT = "cardinal-components:static-init";
    public static final CcaBootstrap INSTANCE = new CcaBootstrap();

    private final List<EntrypointContainer<StaticComponentInitializer>> staticComponentInitializers = FabricLoader.getInstance().getEntrypointContainers(STATIC_INIT_ENTRYPOINT, StaticComponentInitializer.class);

    @VisibleForTesting Collection<Identifier> additionalComponentIds = new ArrayList<>();
    @VisibleForTesting Collection<Identifier> additionalImmutableComponentIds = new ArrayList<>();
    private Map<Identifier, Class<? extends ComponentKey<?>>> generatedComponentTypes = new HashMap<>();

    public CcaBootstrap() {
        super("registering a ComponentType");
    }

    public boolean isGenerated(Class<?> keyClass) {
        if (this.requiresInitialization()) return false;
        return this.generatedComponentTypes.containsValue(keyClass);
    }

    @Nullable
    public Class<? extends ComponentKey<?>> getGeneratedComponentTypeClass(Identifier componentId) {
        this.ensureInitialized();
        assert this.generatedComponentTypes != null;
        return this.generatedComponentTypes.get(componentId);
    }

    @Override
    protected void init() {
        try {
            Set<Identifier> staticComponentTypes = new TreeSet<>(Comparator.comparing(Identifier::toString));
            Set<Identifier> staticImmutableComponentTypes = new TreeSet<>(Comparator.comparing(Identifier::toString));

            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                ModMetadata metadata = mod.getMetadata();
                if (metadata.containsCustomValue("cardinal-components")) {
                    try {
                        for (CustomValue value : metadata.getCustomValue("cardinal-components").getAsArray()) {
                            staticComponentTypes.add(Identifier.of(value.getAsString()));
                        }
                    } catch (ClassCastException | InvalidIdentifierException e) {
                        throw new StaticComponentLoadingException("Failed to load component ids declared by " + metadata.getName() + "(" + metadata.getId() + ")", e);
                    }
                }
                if (metadata.containsCustomValue("cardinal-components-immutable")) {
                    try {
                        for (CustomValue value : metadata.getCustomValue("cardinal-components-immutable").getAsArray()) {
                            staticImmutableComponentTypes.add(Identifier.of(value.getAsString()));
                        }
                    } catch (ClassCastException | InvalidIdentifierException e) {
                        throw new StaticComponentLoadingException("Failed to load component ids declared by " + metadata.getName() + "(" + metadata.getId() + ")", e);
                    }
                }
            }

            for (EntrypointContainer<StaticComponentInitializer> staticInitializer : this.staticComponentInitializers) {
                try {
                    staticComponentTypes.addAll(staticInitializer.getEntrypoint().getSupportedComponentKeys());
                    staticImmutableComponentTypes.addAll(staticInitializer.getEntrypoint().getSupportedImmutableComponentKeys());
                } catch (Throwable e) {
                    ModMetadata badMod = staticInitializer.getProvider().getMetadata();
                    throw new StaticComponentLoadingException(String.format("Exception while querying %s (%s) for supported static component types", badMod.getName(), badMod.getId()), e);
                }
            }

            staticComponentTypes.addAll(this.additionalComponentIds);
            staticImmutableComponentTypes.addAll(this.additionalImmutableComponentIds);

            this.spinStaticContainerItf(staticComponentTypes, staticImmutableComponentTypes);
            this.generatedComponentTypes = this.spinStaticComponentKeys(staticComponentTypes, staticImmutableComponentTypes);
        } catch (IOException | UncheckedIOException e) {
            throw new StaticComponentLoadingException("Failed to load statically defined components", e);
        }
    }

    @Override
    protected void postInit() {
        for (EntrypointContainer<StaticComponentInitializer> staticInitializer : this.staticComponentInitializers) {
            staticInitializer.getEntrypoint().finalizeStaticBootstrap();
        }
    }

    /**
     * Defines a {@link ComponentKey} subclass for every statically declared component, as well as
     * a global {@link ComponentProvider} specialized interface
     * that declares a direct getter for every {@link ComponentKey} that has been scanned by plugins.
     *
     * @param staticComponentKeys           the set of all statically declared {@link ComponentKey} ids
     * @param staticImmutableComponentTypes
     * @return a map of {@link ComponentKey} ids to specialized implementations
     */
    private Map<Identifier, Class<? extends ComponentKey<?>>> spinStaticComponentKeys(Set<Identifier> staticComponentKeys, Set<Identifier> staticImmutableComponentKeys) throws IOException {
        Map<Identifier, Class<? extends ComponentKey<?>>> generatedComponentTypes = new HashMap<>(staticComponentKeys.size());

        for (Identifier componentId : staticComponentKeys) {
            /* generate the component type class */

            ClassNode componentTypeWriter = new ClassNode(CcaAsmHelper.ASM_VERSION);
            String componentTypeName = CcaAsmHelper.STATIC_COMPONENT_TYPE+"Impl";
            componentTypeWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, componentTypeName, null, CcaAsmHelper.COMPONENT_TYPE, null);

            MethodVisitor init = componentTypeWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", COMPONENT_TYPE_INIT_DESC, null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitVarInsn(Opcodes.ALOAD, 2);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.COMPONENT_TYPE, "<init>", COMPONENT_TYPE_INIT_DESC, false);
            init.visitInsn(Opcodes.RETURN);
            init.visitEnd();

            MethodVisitor get = componentTypeWriter.visitMethod(Opcodes.ACC_PROTECTED, "getInternal", COMPONENT_TYPE_GET0_DESC, null, null);
            get.visitCode();
            get.visitVarInsn(Opcodes.ALOAD, 1);
            // stack: object
            get.visitTypeInsn(Opcodes.CHECKCAST, CcaAsmHelper.STATIC_COMPONENT_CONTAINER);
            // stack: generatedComponentContainer
            get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CcaAsmHelper.STATIC_COMPONENT_CONTAINER, CcaAsmHelper.getStaticStorageGetterName(componentId), CcaAsmHelper.STATIC_CONTAINER_GETTER_DESC, false);
            // stack: component
            get.visitInsn(Opcodes.ARETURN);
            get.visitEnd();

            @SuppressWarnings("unchecked") Class<? extends ComponentKey<?>> ct = (Class<? extends ComponentKey<?>>) CcaAsmHelper.generateClass(componentTypeWriter, true, null);
            generatedComponentTypes.put(componentId, ct);
        }

        for (Identifier componentId : staticImmutableComponentKeys) {
            /* generate the component type class */

            ClassNode componentTypeWriter = new ClassNode(CcaAsmHelper.ASM_VERSION);
            String componentTypeName = CcaAsmHelper.STATIC_IMMUTABLE_COMPONENT_TYPE+"Impl";
            componentTypeWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, componentTypeName, null, CcaAsmHelper.IMMUTABLE_COMPONENT_TYPE, null);

            MethodVisitor init = componentTypeWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", IMMUTABLE_COMPONENT_TYPE_INIT_DESC, null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0); // this
            init.visitVarInsn(Opcodes.ALOAD, 1); // id
            init.visitVarInsn(Opcodes.ALOAD, 2); // class
            init.visitVarInsn(Opcodes.ALOAD, 3); // wrapper class
            init.visitVarInsn(Opcodes.ALOAD, 4); // map codec
            init.visitVarInsn(Opcodes.ALOAD, 5); // packet codec
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.IMMUTABLE_COMPONENT_TYPE, "<init>", IMMUTABLE_COMPONENT_TYPE_INIT_DESC, false);
            init.visitInsn(Opcodes.RETURN);
            init.visitEnd();

            MethodVisitor get = componentTypeWriter.visitMethod(Opcodes.ACC_PROTECTED, "getInternal", COMPONENT_TYPE_GET0_DESC, null, null);
            get.visitCode();
            get.visitVarInsn(Opcodes.ALOAD, 1);
            // stack: object
            get.visitTypeInsn(Opcodes.CHECKCAST, CcaAsmHelper.STATIC_COMPONENT_CONTAINER);
            // stack: generatedComponentContainer
            get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CcaAsmHelper.STATIC_COMPONENT_CONTAINER, CcaAsmHelper.getStaticStorageGetterName(componentId), CcaAsmHelper.STATIC_CONTAINER_GETTER_DESC, false);
            // stack: component
            get.visitInsn(Opcodes.ARETURN);
            get.visitEnd();

            @SuppressWarnings("unchecked") Class<? extends ComponentKey<?>> ct = (Class<? extends ComponentKey<?>>) CcaAsmHelper.generateClass(componentTypeWriter, true, null);
            generatedComponentTypes.put(componentId, ct);
        }
        return generatedComponentTypes;
    }

    /**
     * Generate the component container interface implemented by all component containers
     */
    private void spinStaticContainerItf(Set<Identifier> staticComponentTypes, Set<Identifier> staticImmutableComponentTypes) throws IOException {
        ClassNode staticContainerWriter = new ClassNode(CcaAsmHelper.ASM_VERSION);
        staticContainerWriter.visit(Opcodes.V1_8, Opcodes.ACC_ABSTRACT | Opcodes.ACC_PUBLIC, CcaAsmHelper.STATIC_COMPONENT_CONTAINER, null, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, null);

        MethodVisitor init = staticContainerWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CcaAsmHelper.ABSTRACT_COMPONENT_CONTAINER_CTOR_DESC, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.DYNAMIC_COMPONENT_CONTAINER_IMPL, "<init>", CcaAsmHelper.ABSTRACT_COMPONENT_CONTAINER_CTOR_DESC, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitEnd();

        for (Identifier componentId : Iterables.concat(staticComponentTypes, staticImmutableComponentTypes)) {
            MethodVisitor methodWriter = staticContainerWriter.visitMethod(Opcodes.ACC_PUBLIC, CcaAsmHelper.getStaticStorageGetterName(componentId), CcaAsmHelper.STATIC_CONTAINER_GETTER_DESC, null, null);
            methodWriter.visitInsn(Opcodes.ACONST_NULL);
            methodWriter.visitInsn(Opcodes.ARETURN);
            methodWriter.visitEnd();
        }

        staticContainerWriter.visitEnd();
        CcaAsmHelper.generateClass(staticContainerWriter, false, null);
    }

}
