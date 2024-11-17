package org.ladysnake.cca.internal.base;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.MapCodec;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;

public class ImmutableInternals {
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
