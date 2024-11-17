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
package org.ladysnake.cca.internal.base;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import org.ladysnake.cca.api.v3.component.Component;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistryV3;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.ladysnake.cca.internal.base.asm.CcaBootstrap;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class ComponentRegistryImpl implements ComponentRegistryV3 {

    public static final ComponentRegistryImpl INSTANCE = new ComponentRegistryImpl();

    private final Map<Identifier, ComponentKey<?>> keys = new HashMap<>();

    @Override
    public synchronized <T extends Component> ComponentKey<T> getOrCreate(Identifier componentId, Class<T> componentClass) {
        Preconditions.checkArgument(Component.class.isAssignableFrom(componentClass), "Component interface must extend " + Component.class.getCanonicalName());
        // make sure 2+ components cannot get registered at the same time
        @SuppressWarnings("unchecked")
        ComponentKey<T> existing = (ComponentKey<T>) this.get(componentId);

        if (existing != null) {
            if (existing.getComponentClass() != componentClass) {
                throw new IllegalStateException("Registered component " + componentId + " twice with 2 different classes: " + existing.getComponentClass() + ", " + componentClass);
            }
            return existing;
        } else {
            Class<? extends ComponentKey<?>> generated = CcaBootstrap.INSTANCE.getGeneratedComponentTypeClass(componentId);

            if (generated == null) {
                throw new IllegalStateException(componentId + " was not registered through mod metadata or plugin");
            }

            ComponentKey<T> registered = this.instantiateStaticType(generated, componentId, componentClass);
            this.keys.put(componentId, registered);
            return registered;
        }
    }

    @Override
    public synchronized <T extends ImmutableComponent> ImmutableComponentKey<T> getOrCreateImmutable(Identifier componentId, Class<T> immutableComponentClass, @org.jetbrains.annotations.Nullable MapCodec<T> cMapCodec, @org.jetbrains.annotations.Nullable PacketCodec<RegistryByteBuf, T> registryByteBufCPacketCodec) {
        Preconditions.checkArgument(ImmutableComponent.class.isAssignableFrom(immutableComponentClass), "Component interface must extend " + ImmutableComponent.class.getCanonicalName());
        // make sure 2+ components cannot get registered at the same time
        @SuppressWarnings("unchecked")
        ImmutableComponentKey<T> existing = (ImmutableComponentKey<T>) this.get(componentId);

        if (existing != null) {
            if (existing.getImmutableComponentClass() != immutableComponentClass) {
                throw new IllegalStateException("Registered component " + componentId + " twice with 2 different classes: " + existing.getComponentClass() + ", " + immutableComponentClass);
            }
            return existing;
        } else {
            Class<? extends ComponentKey<?>> generated = CcaBootstrap.INSTANCE.getGeneratedComponentTypeClass(componentId);

            if (generated == null) {
                throw new IllegalStateException(componentId + " was not registered through mod metadata or plugin");
            }

            if (!ImmutableComponentKey.class.isAssignableFrom(generated)) {
                throw new IllegalStateException(componentId + " was registered as a classic component, not an immutable one");
            }

            ImmutableComponentKey<T> registered = this.instantiateStaticImmutableType(
                (Class<? extends ImmutableComponentKey<?>>) generated,
                componentId,
                immutableComponentClass,
                cMapCodec,
                registryByteBufCPacketCodec);
            this.keys.put(componentId, registered);
            return registered;
        }
    }

    private <T extends Component> ComponentKey<T> instantiateStaticType(Class<? extends ComponentKey<?>> generated, Identifier componentId, Class<T> componentClass) {
        try {
            @SuppressWarnings("unchecked") ComponentKey<T> ret = (ComponentKey<T>) generated.getConstructor(Identifier.class, Class.class).newInstance(componentId, componentClass);
            return ret;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("Failed to create statically declared component type", e);
        }
    }

    private <T extends ImmutableComponent> ImmutableComponentKey<T> instantiateStaticImmutableType(Class<? extends ImmutableComponentKey<?>> generated, Identifier componentId, Class<T> componentClass, @org.jetbrains.annotations.Nullable MapCodec<T> mapCodec, @org.jetbrains.annotations.Nullable PacketCodec<RegistryByteBuf, T> packetCodec) {
        try {
            @SuppressWarnings("unchecked") ImmutableComponentKey<T> ret = (ImmutableComponentKey<T>) generated.getConstructor(Identifier.class, Class.class, Class.class, MapCodec.class, PacketCodec.class)
                .newInstance(componentId, componentClass, ImmutableComponentWrapper.class, mapCodec, packetCodec);
            return ret;
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("Failed to create statically declared component type", e);
        }
    }

    @Nullable
    @Override
    public ComponentKey<?> get(Identifier id) {
        return this.keys.get(id);
    }

    @Override
    public Stream<ComponentKey<?>> stream() {
        return new HashSet<>(this.keys.values()).stream();
    }

    @VisibleForTesting
    void clear(Identifier id) {
        this.keys.remove(id);
    }
}
