package org.ladysnake.cca.api.v3.component.immutable;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.ladysnake.cca.api.v3.component.ComponentKey;

public abstract class ImmutableComponentKey<C extends ImmutableComponent> extends ComponentKey<ImmutableComponentWrapper<C, ?>> {
    private final @Nullable MapCodec<C> mapCodec;
    private final @Nullable PacketCodec<RegistryByteBuf, C> packetCodec;
    private final Class<C> immutableComponentClass;

    protected ImmutableComponentKey(Identifier id, Class<C> immutableComponentClass, Class<ImmutableComponentWrapper<C, ?>> wrapperClass, @Nullable MapCodec<C> mapCodec, @Nullable PacketCodec<RegistryByteBuf, C> packetCodec) {
        super(id, wrapperClass);
        this.immutableComponentClass = immutableComponentClass;
        this.mapCodec = mapCodec;
        this.packetCodec = packetCodec;
    }

    public Class<C> getImmutableComponentClass() {
        return this.immutableComponentClass;
    }

    public MapCodec<C> getMapCodec() {
        return this.mapCodec;
    }

    public PacketCodec<RegistryByteBuf, C> getPacketCodec() {
        return this.packetCodec;
    }
}
