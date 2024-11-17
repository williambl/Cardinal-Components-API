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

import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import org.ladysnake.cca.api.v3.component.ComponentFactory;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponent;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentFactory;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentKey;
import org.ladysnake.cca.api.v3.component.immutable.ImmutableComponentWrapper;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class CcaImmutableBootstrap {
    public static <C extends ImmutableComponent, O, W extends ImmutableComponentWrapper<C, O>> Class<W> makeWrapper(
        ImmutableComponentKey<C> key,
        Class<O> targetClass
    ) throws IOException {
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

        writer.visitEnd();
        return (Class<W>) CcaAsmHelper.generateClass(writer, false, null);
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
