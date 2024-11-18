package org.ladysnake.cca.api.v3.component.immutable;

import org.ladysnake.cca.api.v3.component.load.ClientLoadAwareComponent;
import org.ladysnake.cca.api.v3.component.load.ClientUnloadAwareComponent;
import org.ladysnake.cca.api.v3.component.load.ServerLoadAwareComponent;
import org.ladysnake.cca.api.v3.component.load.ServerUnloadAwareComponent;
import org.ladysnake.cca.api.v3.component.tick.ClientTickingComponent;
import org.ladysnake.cca.api.v3.component.tick.ServerTickingComponent;

import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public record ImmutableComponentCallbackType<I>(Class<I> itf,
                                                String methodName,
                                                MethodType exposedType,
                                                MethodType implType) {
    public ImmutableComponentCallbackType {
        if (!itf.isInterface()
            || Arrays.stream(itf.getDeclaredMethods())
            .filter(m -> Modifier.isAbstract(m.getModifiers()))
            .count() != 1) {
            throw new IllegalArgumentException("ImmutableComponentCallbackType accepts only functional interfaces");
        }
    }

    public static <I> ImmutableComponentCallbackType<I> fromFunctionalInterface(Class<I> itf) {
        var method = Arrays.stream(itf.getDeclaredMethods())
            .filter(m -> Modifier.isAbstract(m.getModifiers()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("ImmutableComponentCallbackType accepts only functional interfaces"));
        String name = method.getName();
        var type = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
        var implType = type.insertParameterTypes(0, ImmutableComponentWrapper.class);
        return new ImmutableComponentCallbackType<>(itf, name, type, implType);
    }

    public static final ImmutableComponentCallbackType<ServerTickingComponent> SERVER_TICK = fromFunctionalInterface(ServerTickingComponent.class);
    public static final ImmutableComponentCallbackType<ClientTickingComponent> CLIENT_TICK = fromFunctionalInterface(ClientTickingComponent.class);
    public static final ImmutableComponentCallbackType<ServerLoadAwareComponent> SERVER_LOAD = fromFunctionalInterface(ServerLoadAwareComponent.class);
    public static final ImmutableComponentCallbackType<ClientLoadAwareComponent> CLIENT_LOAD = fromFunctionalInterface(ClientLoadAwareComponent.class);
    public static final ImmutableComponentCallbackType<ServerUnloadAwareComponent> SERVER_UNLOAD = fromFunctionalInterface(ServerUnloadAwareComponent.class);
    public static final ImmutableComponentCallbackType<ClientUnloadAwareComponent> CLIENT_UNLOAD = fromFunctionalInterface(ClientUnloadAwareComponent.class);
}
