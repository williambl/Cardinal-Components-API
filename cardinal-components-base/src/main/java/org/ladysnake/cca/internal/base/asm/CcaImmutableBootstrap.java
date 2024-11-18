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

import com.mojang.datafixers.util.Pair;
import org.ladysnake.cca.api.v3.component.ComponentFactory;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentFactory;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.ladysnake.cca.internal.base.ImmutableInternals;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;

public final class CcaImmutableBootstrap {
    public static <C extends ImmutableComponent, O, W extends ImmutableComponentWrapper<C, O>> Class<W> makeWrapper(
        ImmutableComponentKey<C> key,
        Class<O> targetClass,
        ImmutableComponent.Modifier<C,O> serverTicker,
        ImmutableComponent.Modifier<C,O> clientTicker,
        ImmutableComponent.Modifier<C,O> serverOnLoad,
        ImmutableComponent.Modifier<C,O> clientOnLoad
    ) throws IOException, NoSuchMethodException, IllegalAccessException {
        ClassNode writer = new ClassNode(CcaAsmHelper.ASM_VERSION);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CcaAsmHelper.STATIC_IMMUTABLE_COMPONENT_WRAPPER + "$" + CcaAsmHelper.getJavaIdentifierName(key.getId()), null, CcaAsmHelper.IMMUTABLE_COMPONENT_WRAPPER, null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CcaAsmHelper.IMMUTABLE_COMPONENT_WRAPPER_CTOR_DESC, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0); // this
        init.visitVarInsn(Opcodes.ALOAD, 1); // key
        init.visitVarInsn(Opcodes.ALOAD, 2); // owner
        init.visitVarInsn(Opcodes.ALOAD, 3); // data
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, CcaAsmHelper.IMMUTABLE_COMPONENT_WRAPPER, "<init>", CcaAsmHelper.IMMUTABLE_COMPONENT_WRAPPER_CTOR_DESC, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitEnd();

        if (key.getMapCodec() != null) {
            MethodVisitor readFromNbt = writer.visitMethod(Opcodes.ACC_PUBLIC, "readFromNbt", CcaAsmHelper.COMPONENT_READ_FROM_NBT_DESC, null, null);
            readFromNbt.visitVarInsn(Opcodes.ALOAD, 0); // this
            readFromNbt.visitVarInsn(Opcodes.ALOAD, 1); // nbt
            readFromNbt.visitVarInsn(Opcodes.ALOAD, 2); // registries
            readFromNbt.visitMethodInsn(Opcodes.INVOKESTATIC, CcaAsmHelper.IMMUTABLE_INTERNALS, "wrapperRead", CcaAsmHelper.IMMUTABLE_WRAPPER_READ_DESC, false);
            readFromNbt.visitInsn(Opcodes.RETURN);
            readFromNbt.visitEnd();

            MethodVisitor writeToNbt = writer.visitMethod(Opcodes.ACC_PUBLIC, "writeToNbt", CcaAsmHelper.COMPONENT_WRITE_TO_NBT_DESC, null, null);
            writeToNbt.visitVarInsn(Opcodes.ALOAD, 0); // this
            writeToNbt.visitVarInsn(Opcodes.ALOAD, 1); // nbt
            writeToNbt.visitVarInsn(Opcodes.ALOAD, 2); // registries
            writeToNbt.visitMethodInsn(Opcodes.INVOKESTATIC, CcaAsmHelper.IMMUTABLE_INTERNALS, "wrapperWrite", CcaAsmHelper.IMMUTABLE_WRAPPER_WRITE_DESC, false);
            writeToNbt.visitInsn(Opcodes.RETURN);
            writeToNbt.visitEnd();
        }

        if (key.getPacketCodec() != null) {
            writer.interfaces.add(CcaAsmHelper.AUTO_SYNCED_COMPONENT);

            MethodVisitor applySyncPacket = writer.visitMethod(Opcodes.ACC_PUBLIC, "applySyncPacket", CcaAsmHelper.AUTO_SYNCED_COMPONENT_APPLY_SYNC_PACKET_DESC, null, null);
            applySyncPacket.visitVarInsn(Opcodes.ALOAD, 0); // this
            applySyncPacket.visitVarInsn(Opcodes.ALOAD, 1); // buf
            applySyncPacket.visitMethodInsn(Opcodes.INVOKESTATIC, CcaAsmHelper.IMMUTABLE_INTERNALS, "wrapperApplySync", CcaAsmHelper.IMMUTABLE_WRAPPER_APPLY_SYNC_DESC, false);
            applySyncPacket.visitInsn(Opcodes.RETURN);
            applySyncPacket.visitEnd();

            MethodVisitor writeSyncPacket = writer.visitMethod(Opcodes.ACC_PUBLIC, "writeSyncPacket", CcaAsmHelper.AUTO_SYNCED_COMPONENT_WRITE_SYNC_PACKET_DESC, null, null);
            writeSyncPacket.visitVarInsn(Opcodes.ALOAD, 0); // this
            writeSyncPacket.visitVarInsn(Opcodes.ALOAD, 1); // buf
            writeSyncPacket.visitMethodInsn(Opcodes.INVOKESTATIC, CcaAsmHelper.IMMUTABLE_INTERNALS, "wrapperWriteSync", CcaAsmHelper.IMMUTABLE_WRAPPER_WRITE_SYNC_DESC, false);
            writeSyncPacket.visitInsn(Opcodes.RETURN);
            writeSyncPacket.visitEnd();
        }

        if (serverTicker != null) {
            ImmutableInternals.serverTickHandlers.put(Pair.of(key.getId(), targetClass), serverTicker);
            makeTicker(key, targetClass, writer, CcaAsmHelper.SERVER_TICKING_COMPONENT, "serverTick", CcaAsmHelper.SERVER_TICK_DESC, CcaAsmHelper.IMMUTABLE_WRAPPER_SERVER_TICK_DESC);
        }
        if (clientTicker != null) {
            ImmutableInternals.clientTickHandlers.put(Pair.of(key.getId(), targetClass), clientTicker);
            makeTicker(key, targetClass, writer, CcaAsmHelper.CLIENT_TICKING_COMPONENT, "clientTick", CcaAsmHelper.CLIENT_TICK_DESC, CcaAsmHelper.IMMUTABLE_WRAPPER_CLIENT_TICK_DESC);
        }
        //todo load/unload handlers

        writer.visitEnd();
        return (Class<W>) CcaAsmHelper.generateClass(writer, false, null);
    }

    private static <C extends ImmutableComponent, O> void makeTicker(ImmutableComponentKey<C> key, Class<O> targetClass, ClassNode writer, String interfaceName, String methodName, String methodDesc, String dynMethodDesc) {
        writer.interfaces.add(interfaceName);
        MethodVisitor onTick = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, methodDesc, null, null);
        onTick.visitVarInsn(Opcodes.ALOAD, 0); // this
        onTick.visitInvokeDynamicInsn(
            methodName,
            dynMethodDesc,
            new Handle(
                H_INVOKESTATIC,
                CcaAsmHelper.IMMUTABLE_INTERNALS,
                "bootstrap",
                CcaAsmHelper.IMMUTABLE_BSM_DESC,
                false),
            key.getId().toString(),
            Type.getType(targetClass));
        onTick.visitInsn(Opcodes.RETURN);
        onTick.visitEnd();
    }

    public static <C extends ImmutableComponent, O, W extends ImmutableComponentWrapper<C, O>> ComponentFactory<O, W> makeFactory(
        ImmutableComponentKey<C> key,
        Class<O> targetClass,
        Class<W> wrapperClass,
        ImmutableComponentFactory<O, C> dataFactory
    ) throws NoSuchMethodException, IllegalAccessException {
        var constructor = MethodHandles.lookup()
            .findConstructor(wrapperClass, MethodType.methodType(void.class, ImmutableComponentKey.class, Object.class, ImmutableComponent.class))
            .bindTo(key);
        return o -> {
            try {
                return (W) constructor.invoke(o, dataFactory.createComponent(o));
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }
}
