package org.ladysnake.cca.internal.base;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentCallbackType;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.ladysnake.cca.api.v3.component.load.ClientLoadAwareComponent;
import org.ladysnake.cca.api.v3.component.load.ClientUnloadAwareComponent;
import org.ladysnake.cca.api.v3.component.load.ServerLoadAwareComponent;
import org.ladysnake.cca.api.v3.component.load.ServerUnloadAwareComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;
import org.ladysnake.cca.internal.base.asm.CcaAsmHelper;

import java.lang.invoke.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ImmutableInternals {
    private static final MethodHandle SET;
    private static final MethodHandle DECONSTRUCT_WRAPPER;
    private static final MethodHandle RUN_TRANSFORMER;

    static {
        try {
            DECONSTRUCT_WRAPPER = MethodHandles.lookup().findStatic(ImmutableInternals.class, "deconstructWrapper", MethodType.methodType(Object.class.arrayType(), ImmutableComponentWrapper.class));
            SET = MethodHandles.lookup().findVirtual(ImmutableComponentWrapper.class, "setData", MethodType.methodType(void.class, ImmutableComponent.class));
            RUN_TRANSFORMER = MethodHandles.lookup().findStatic(ImmutableInternals.class, "runTransformer", MethodType.methodType(void.class, ImmutableComponent.Modifier.class, ImmutableComponentWrapper.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    //TODO id and type do not uniquely describe a component implementation - e.g. predicates in entity component registration
    public static final Map<ImmutableComponentCallbackType<?>, Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>>> listeners = new HashMap<>();
    public static final Set<ImmutableComponentCallbackType<?>> CALLBACK_TYPES = Set.of(
        ImmutableComponentCallbackType.SERVER_TICK,
        ImmutableComponentCallbackType.CLIENT_TICK,
        ImmutableComponentCallbackType.SERVER_LOAD,
        ImmutableComponentCallbackType.CLIENT_LOAD,
        ImmutableComponentCallbackType.SERVER_UNLOAD,
        ImmutableComponentCallbackType.CLIENT_UNLOAD
    );

    public static <C extends ImmutableComponent, E extends Entity> void addListener(ImmutableComponentKey<C> key, Class<E> target, ImmutableComponentCallbackType<?> type, ImmutableComponent.Modifier<C, E> modifier) {
        listeners.computeIfAbsent(type, $ -> new HashMap<>()).put(Pair.of(key.getId(), target), modifier);
    }

    public static Object bootstrap(MethodHandles.Lookup lookup, String methodName, TypeDescriptor type,
                                   String id,
                                   Type targetClass) throws Throwable {
        MethodType methodType;
        if (type instanceof MethodType mt) {
            methodType = mt;
        } else {
            methodType = null;
            if (!MethodHandle.class.equals(type))
                throw new IllegalArgumentException(type.toString());
        }
        ImmutableComponentCallbackType<?> callbackType = CALLBACK_TYPES.stream()
            .filter(t -> t.methodName().equals(methodName))
            .filter(t -> methodType == null || t.implType().equals(methodType))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Invalid method name/type: %s:%s".formatted(methodName, methodType == null ? "?" : methodType.descriptorString())));
        MethodHandle handle = makeModifierHandler(lookup, id, targetClass, listeners.getOrDefault(callbackType, Map.of()));
        return methodType != null ? new ConstantCallSite(handle) : handle;
    }

    private static MethodHandle makeModifierHandler(MethodHandles.Lookup lookup, String id, Type targetClass, Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> handlers) throws NoSuchMethodException, IllegalAccessException {
        var modifier = handlers.get(Pair.of(Identifier.of(id), targetClass));
        if (modifier == null) {
            return MethodHandles.empty(MethodType.methodType(void.class, ImmutableComponentWrapper.class));
        }

        return RUN_TRANSFORMER.bindTo(modifier);
    }

    public static <C extends ImmutableComponent, O> void runTransformer(ImmutableComponent.Modifier<C, O> transformer, ImmutableComponentWrapper<C, O> wrapper) {
        wrapper.setData(transformer.modify(wrapper.getData(), wrapper.getOwner()));
    }

    public static <C extends ImmutableComponent, O> Object[] deconstructWrapper(ImmutableComponentWrapper<C, O> wrapper) {
        return new Object[] {wrapper.getData(), wrapper.getOwner()};
    }

    public static <C extends ImmutableComponent> void wrapperRead(ImmutableComponentWrapper<C, ?> wrapper, NbtCompound compound, RegistryWrapper.WrapperLookup registries) {
        var ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        var decoded = wrapper.getKey().getMapCodec().decode(ops, ops.getMap(compound).getOrThrow())
            .getOrThrow(e -> new RuntimeException("Error decoding component %s:\n%s".formatted(wrapper.getKey().getId(), e)));
        wrapper.setData(decoded);
    }

    public static <C extends ImmutableComponent> void wrapperWrite(ImmutableComponentWrapper<C, ?> wrapper, NbtCompound compound, RegistryWrapper.WrapperLookup registries) {
        var ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        wrapper.getKey().getMapCodec().encode(wrapper.getData(), ops, ops.mapBuilder())
            .build(compound)
            .getOrThrow(e -> new RuntimeException("Error encoding component %s:\n%s".formatted(wrapper.getKey().getId(), e)));
    }

    public static <C extends ImmutableComponent> void wrapperApplySync(ImmutableComponentWrapper<C, ?> wrapper, RegistryByteBuf buf) {
        var decoded = wrapper.getKey().getPacketCodec().decode(buf);
        wrapper.setData(decoded);
    }

    public static <C extends ImmutableComponent> void wrapperWriteSync(ImmutableComponentWrapper<C, ?> wrapper, RegistryByteBuf buf) {
        wrapper.getKey().getPacketCodec().encode(buf, wrapper.getData());
    }
}
