package org.ladysnake.cca.internal.base;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.ladysnake.cca.internal.base.asm.CcaAsmHelper;

import java.lang.invoke.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class ImmutableInternals {
    private static final MethodHandle SET;
    private static final MethodHandle DECONSTRUCT_WRAPPER;

    static {
        try {
            DECONSTRUCT_WRAPPER = MethodHandles.lookup().findStatic(ImmutableInternals.class, "deconstructWrapper", MethodType.methodType(Object.class.arrayType(), ImmutableComponentWrapper.class));
            SET = MethodHandles.lookup().findVirtual(ImmutableComponentWrapper.class, "setData", MethodType.methodType(void.class, ImmutableComponent.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    //TODO id and type do not uniquely describe a component implementation - e.g. predicates in entity component registration
    public static final Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> serverTickHandlers = new HashMap<>();
    public static final Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> clientTickHandlers = new HashMap<>();
    public static final Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> loadServersideHandlers = new HashMap<>();
    public static final Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> loadClientsideHandlers = new HashMap<>();
    public static final Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> unloadServersideHandlers = new HashMap<>();
    public static final Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> unloadClientsideHandlers = new HashMap<>();

    public static Object bootstrap(MethodHandles.Lookup lookup, String methodName, TypeDescriptor type,
                                   String id,
                                   Type targetClass) throws Throwable {
        MethodType methodType;
        if (type instanceof MethodType mt)
            methodType = mt;
        else {
            methodType = null;
            if (!MethodHandle.class.equals(type))
                throw new IllegalArgumentException(type.toString());
        }
        MethodHandle handle = switch (methodName) {
            case "serverTick" -> makeModifierHandler(lookup, id, targetClass, serverTickHandlers);
            case "clientTick" -> makeModifierHandler(lookup, id, targetClass, clientTickHandlers);
            case "loadServerside" -> makeModifierHandler(lookup, id, targetClass, loadServersideHandlers);
            case "loadClientside" -> makeModifierHandler(lookup, id, targetClass, loadClientsideHandlers);
            case "unloadServerside" -> makeModifierHandler(lookup, id, targetClass, unloadServersideHandlers);
            case "unloadClientside" -> makeModifierHandler(lookup, id, targetClass, unloadClientsideHandlers);
            default -> throw new IllegalArgumentException(methodName);
        };
        return methodType != null ? new ConstantCallSite(handle) : handle;
    }

    private static MethodHandle makeModifierHandler(MethodHandles.Lookup lookup, String id, Type targetClass, Map<Pair<Identifier, Type>, ImmutableComponent.Modifier<?, ?>> handlers) throws NoSuchMethodException, IllegalAccessException {
        var modifier = handlers.get(Pair.of(Identifier.of(id), targetClass));
        if (modifier == null) {
            return MethodHandles.empty(MethodType.methodType(void.class, ImmutableComponentWrapper.class));
        }
        // todo for tomorrow: work out how to get a MethodHandle from a lambda / anonymous class
        // so that this doesn't happen:
        // java.lang.IllegalAccessException: symbolic reference class is not accessible: class org.ladysnake.cca.test.entity.CcaEntityTestMod$$Lambda/0x0000000601c90428

        // (c, o) -> c
        var transform = lookup.bind(modifier, "modify", CcaAsmHelper.MODIFIER_MODIFY_TYPE);
        // (c, o) -> void
        var tfAndSet = MethodHandles.filterReturnValue(transform, SET);
        // ([c,o]) -> void
        var spreadTfAndSet = MethodHandles.spreadInvoker(tfAndSet.type(), 0).bindTo(tfAndSet);
        // w -> void
        return MethodHandles.filterArguments(spreadTfAndSet, 0, DECONSTRUCT_WRAPPER);
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
