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
package org.ladysnake.cca.internal.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.Component;
import org.ladysnake.cca.api.v3.component.ComponentContainer;
import org.ladysnake.cca.api.v3.component.ComponentFactory;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentFactory;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.ladysnake.cca.api.v3.entity.EntityComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.entity.EntityComponentInitializer;
import org.ladysnake.cca.api.v3.entity.RespawnableComponent;
import org.ladysnake.cca.api.v3.entity.RespawnCopyStrategy;
import org.ladysnake.cca.internal.base.LazyDispatcher;
import org.ladysnake.cca.internal.base.QualifiedComponentFactory;
import org.ladysnake.cca.internal.base.asm.CcaImmutableBootstrap;
import org.ladysnake.cca.internal.base.asm.StaticComponentLoadingException;
import org.ladysnake.cca.internal.base.asm.StaticComponentPluginBase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class StaticEntityComponentPlugin extends LazyDispatcher implements EntityComponentFactoryRegistry {
    public static final StaticEntityComponentPlugin INSTANCE = new StaticEntityComponentPlugin();

    private StaticEntityComponentPlugin() {
        super("instantiating an entity");
    }

    private static String getSuffix(Class<?> entityClass) {
        String simpleName = entityClass.getSimpleName();
        return String.format("EntityImpl_%s_%s", simpleName, Integer.toHexString(entityClass.getName().hashCode()));
    }

    private final List<PredicatedComponentFactory<?>> dynamicFactories = new ArrayList<>();
    private final Map<Class<? extends Entity>, Map<ComponentKey<?>, QualifiedComponentFactory<ComponentFactory<? extends Entity, ?>>>> componentFactories = new HashMap<>();

    public boolean requiresStaticFactory(Class<? extends Entity> entityClass) {
        this.ensureInitialized();

        for (PredicatedComponentFactory<?> dynamicFactory : this.dynamicFactories) {
            dynamicFactory.tryRegister(entityClass);
        }

        return entityClass == Entity.class || this.componentFactories.containsKey(entityClass);
    }

    public ComponentContainer.Factory<Entity> buildDedicatedFactory(Class<? extends Entity> entityClass) {
        this.ensureInitialized();

        var compiled = new LinkedHashMap<>(this.componentFactories.getOrDefault(entityClass, Collections.emptyMap()));
        Class<?> type = entityClass;

        while (type != Entity.class) {
            type = type.getSuperclass();
            this.componentFactories.getOrDefault(type, Collections.emptyMap()).forEach(compiled::putIfAbsent);
        }

        ComponentContainer.Factory.Builder<Entity> builder = ComponentContainer.Factory.builder(Entity.class)
            .factoryNameSuffix(getSuffix(entityClass));

        for (var entry : compiled.entrySet()) {
            addToBuilder(builder, entry);
        }

        return builder.build();
    }

    private <C extends Component> void addToBuilder(ComponentContainer.Factory.Builder<Entity> builder, Map.Entry<ComponentKey<?>, QualifiedComponentFactory<ComponentFactory<? extends Entity, ?>>> entry) {
        @SuppressWarnings("unchecked") var key = (ComponentKey<C>) entry.getKey();
        @SuppressWarnings("unchecked") var factory = (ComponentFactory<Entity, C>) entry.getValue().factory();
        @SuppressWarnings("unchecked") var impl = (Class<C>) entry.getValue().impl();
        builder.component(key, impl, factory, entry.getValue().dependencies());
    }

    @Override
    protected void init() {
        StaticComponentPluginBase.processInitializers(
            StaticComponentPluginBase.getComponentEntrypoints("cardinal-components-entity", EntityComponentInitializer.class),
            initializer -> initializer.registerEntityComponentFactories(this)
        );
    }

    @Override
    public <C extends Component, E extends Entity> void registerFor(Class<E> target, ComponentKey<C> type, ComponentFactory<E, ? extends C> factory) {
        this.checkLoading(EntityComponentFactoryRegistry.class, "register");
        this.register0(target, type, new QualifiedComponentFactory<>(factory, type.getComponentClass(), Set.of()));
    }

    @Override
    public <C extends Component> void registerFor(Predicate<Class<? extends Entity>> test, ComponentKey<C> type, ComponentFactory<Entity, C> factory) {
        this.dynamicFactories.add(new PredicatedComponentFactory<>(test, type, new QualifiedComponentFactory<>(factory, type.getComponentClass(), Set.of())));
    }

    @Override
    public <C extends Component, E extends Entity> Registration<C, E> beginRegistration(Class<E> target, ComponentKey<C> key) {
        return new RegistrationImpl<>(target, key);
    }

    @Override
    public <C extends ImmutableComponent, E extends Entity> ImmutableRegistration<C, E> beginImmutableRegistration(Class<E> target, ImmutableComponentKey<C> key) {
        return new ImmutableRegistrationImpl<>(target, key);
    }

    @Override
    public <C extends RespawnableComponent<? super C>> void registerForPlayers(ComponentKey<? super C> key, ComponentFactory<PlayerEntity, C> factory) {
        this.registerForPlayers(key, factory, CardinalEntityInternals.DEFAULT_COPY_STRATEGY);
    }

    @Override
    public <C extends Component, P extends C> void registerForPlayers(ComponentKey<C> key, ComponentFactory<PlayerEntity, P> factory, RespawnCopyStrategy<? super P> respawnStrategy) {
        this.registerFor(PlayerEntity.class, key, factory);
        CardinalEntityInternals.registerRespawnCopyStrat(key, PlayerEntity.class, respawnStrategy);
    }

    private <C extends Component, F extends C, E extends Entity> void register0(Class<? extends E> target, ComponentKey<? super C> key, QualifiedComponentFactory<ComponentFactory<E, F>> factory) {
        var specializedMap = this.componentFactories.computeIfAbsent(target, t -> new LinkedHashMap<>());
        var previousFactory = specializedMap.get(key);

        if (previousFactory != null) {
            throw new StaticComponentLoadingException("Duplicate factory declarations for " + key.getId() + " on " + target + ": " + factory + " and " + previousFactory);
        }

        @SuppressWarnings("unchecked") var factory1 = (QualifiedComponentFactory<ComponentFactory<? extends Entity, ?>>) (QualifiedComponentFactory<?>) factory;
        specializedMap.put(key, factory1);
        QualifiedComponentFactory.checkNoDependencyCycles(specializedMap);
    }

    private final class PredicatedComponentFactory<C extends Component> {
        private final Predicate<Class<? extends Entity>> predicate;
        private final ComponentKey<? super C> type;
        private final QualifiedComponentFactory<ComponentFactory<Entity, C>> factory;

        public PredicatedComponentFactory(Predicate<Class<? extends Entity>> predicate, ComponentKey<? super C> type, QualifiedComponentFactory<ComponentFactory<Entity, C>> factory) {
            this.type = type;
            this.factory = factory;
            this.predicate = predicate;
        }

        public void tryRegister(Class<? extends Entity> clazz) {
            if (this.predicate.test(clazz)) {
                StaticEntityComponentPlugin.this.register0(clazz, this.type, this.factory);
            }
        }
    }

    private final class RegistrationImpl<C extends Component, E extends Entity> implements Registration<C, E> {
        private final Class<E> target;
        private final ComponentKey<? super C> key;
        private final Set<ComponentKey<?>> dependencies;
        private Class<C> componentClass;
        private Predicate<Class<? extends E>> test;

        RegistrationImpl(Class<E> target, ComponentKey<C> key) {
            this.target = target;
            this.componentClass = key.getComponentClass();
            this.dependencies = new LinkedHashSet<>();
            this.test = null;
            this.key = key;
        }

        @Override
        public Registration<C, E> filter(Predicate<Class<? extends E>> test) {
            this.test = this.test == null ? test : this.test.and(test);
            return this;
        }

        @Override
        public Registration<C, E> after(ComponentKey<?> dependency) {
            this.dependencies.add(dependency);
            return this;
        }

        @Override
        public <I extends C> Registration<I, E> impl(Class<I> impl) {
            @SuppressWarnings("unchecked") RegistrationImpl<I, E> ret = (RegistrationImpl<I, E>) this;
            ret.componentClass = impl;
            return ret;
        }

        @Override
        public Registration<C, E> respawnStrategy(RespawnCopyStrategy<? super C> strategy) {
            CardinalEntityInternals.registerRespawnCopyStrat(this.key, this.target, strategy);
            return this;
        }

        @Override
        public void end(ComponentFactory<E, C> factory) {
            StaticEntityComponentPlugin.this.checkLoading(Registration.class, "end");
            if (this.test == null) {
                StaticEntityComponentPlugin.this.register0(
                    this.target,
                    this.key,
                    new QualifiedComponentFactory<>(factory, this.componentClass, this.dependencies)
                );
            } else {
                StaticEntityComponentPlugin.this.dynamicFactories.add(new PredicatedComponentFactory<>(
                    c -> this.target.isAssignableFrom(c) && this.test.test(c.asSubclass(this.target)),
                    this.key,
                    new QualifiedComponentFactory<>(
                        entity -> factory.createComponent(this.target.cast(entity)),
                        this.componentClass,
                        this.dependencies
                    )
                ));
            }
        }
    }

    private final class ImmutableRegistrationImpl<C extends ImmutableComponent, E extends Entity> implements ImmutableRegistration<C, E> {
        private final Class<E> target;
        private final ImmutableComponentKey<C> key;
        private final Set<ComponentKey<?>> dependencies;
        private @Nullable ImmutableComponent.Modifier<C, E> serverTicker;
        private @Nullable ImmutableComponent.Modifier<C, E> clientTicker;
        private @Nullable ImmutableComponent.Modifier<C, E> serverOnLoad;
        private @Nullable ImmutableComponent.Modifier<C, E> clientOnLoad;
        private Predicate<Class<? extends E>> test;

        ImmutableRegistrationImpl(Class<E> target, ImmutableComponentKey<C> key) {
            this.target = target;
            this.dependencies = new LinkedHashSet<>();
            this.test = null;
            this.key = key;
        }

        @Override
        public ImmutableRegistration<C, E> filter(Predicate<Class<? extends E>> test) {
            this.test = this.test == null ? test : this.test.and(test);
            return this;
        }

        @Override
        public ImmutableRegistration<C, E> after(ComponentKey<?> dependency) {
            this.dependencies.add(dependency);
            return this;
        }


        @Override
        public ImmutableRegistration<C, E> respawnStrategy(RespawnCopyStrategy<? super ImmutableComponentWrapper<C, E>> strategy) {
            CardinalEntityInternals.registerRespawnCopyStrat(this.key, this.target, strategy);
            return this;
        }

        @Override
        public ImmutableRegistration<C, E> onServerTick(ImmutableComponent.Modifier<C, E> modifier) {
            this.serverTicker = modifier;
            return this;
        }

        @Override
        public ImmutableRegistration<C, E> onClientTick(ImmutableComponent.Modifier<C, E> modifier) {
            this.clientTicker = modifier;
            return this;
        }

        @Override
        public ImmutableRegistration<C, E> onServerLoad(ImmutableComponent.Modifier<C, E> modifier) {
            this.serverOnLoad = modifier;
            return this;
        }

        @Override
        public ImmutableRegistration<C, E> onClientLoad(ImmutableComponent.Modifier<C, E> modifier) {
            this.clientOnLoad = modifier;
            return this;
        }

        @Override
        public void end(ImmutableComponentFactory<E, C> factory) {
            try {
                StaticEntityComponentPlugin.this.checkLoading(Registration.class, "end");
                Class<? extends ImmutableComponentWrapper<C, E>> componentClass = CcaImmutableBootstrap.makeWrapper(
                    this.key,
                    this.target,
                    this.serverTicker,
                    this.clientTicker,
                    this.serverOnLoad,
                    this.clientOnLoad
                );
                ComponentFactory<E, ? extends ImmutableComponentWrapper<C, E>> componentFactory = CcaImmutableBootstrap.makeFactory(this.key, this.target, componentClass, factory);
                if (this.test == null) {
                    StaticEntityComponentPlugin.this.register0(
                        this.target,
                        this.key,
                        new QualifiedComponentFactory<>(componentFactory, componentClass, this.dependencies)
                    );
                } else {
                    StaticEntityComponentPlugin.this.dynamicFactories.add(new PredicatedComponentFactory<>(
                        c -> this.target.isAssignableFrom(c) && this.test.test(c.asSubclass(this.target)),
                        this.key,
                        new QualifiedComponentFactory<>(
                            entity -> componentFactory.createComponent(this.target.cast(entity)),
                            componentClass,
                            this.dependencies
                        )
                    ));
                }
            } catch (IOException | NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
