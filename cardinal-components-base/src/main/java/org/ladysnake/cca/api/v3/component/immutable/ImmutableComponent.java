package org.ladysnake.cca.api.v3.component.immutable;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import org.ladysnake.cca.api.v3.component.Component;

public interface ImmutableComponent {
    interface Ticker<C extends ImmutableComponent, O> {
        C onTick(C component, O attachedTo);
    }
}
