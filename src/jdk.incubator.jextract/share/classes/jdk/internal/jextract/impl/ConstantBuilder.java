/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.internal.jextract.impl;

import jdk.incubator.foreign.FunctionDescriptor;
import jdk.incubator.foreign.GroupLayout;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.SequenceLayout;
import jdk.incubator.foreign.ValueLayout;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ConstantBuilder extends ClassSourceBuilder {

    // set of names generates already
    private final Map<String, Constant> namesGenerated = new HashMap<>();

    public ConstantBuilder(JavaSourceBuilder enclosing, String className) {
        super(enclosing, Kind.CLASS, className);
    }

    String memberMods() {
        return kind == ClassSourceBuilder.Kind.CLASS ?
                "static final " : "";
    }

    // public API

    public Constant addLayout(String javaName, MemoryLayout layout) {
        return emitIfAbsent(javaName, Constant.Kind.LAYOUT,
                () -> emitLayoutField(javaName, layout));
    }

    public Constant addFieldVarHandle(String javaName, String nativeName, VarInfo varInfo,
                                      String rootJavaName, List<String> prefixElementNames) {
        return addVarHandle(javaName, nativeName, varInfo, rootJavaName, prefixElementNames);
    }

    public Constant addGlobalVarHandle(String javaName, String nativeName, VarInfo varInfo) {
        return addVarHandle(javaName, nativeName, varInfo, null, List.of());
    }

    private Constant addVarHandle(String javaName, String nativeName, VarInfo varInfo,
                                String rootLayoutName, List<String> prefixElementNames) {
        return emitIfAbsent(javaName, Constant.Kind.VAR_HANDLE,
                () -> emitVarHandleField(javaName, nativeName, varInfo, rootLayoutName, prefixElementNames));
    }

    public Constant addMethodHandle(String javaName, String nativeName, FunctionInfo functionInfo, boolean virtual) {
        return emitIfAbsent(javaName, Constant.Kind.METHOD_HANDLE,
                () -> emitMethodHandleField(javaName, nativeName, functionInfo, virtual));
    }

    public Constant addSegment(String javaName, String nativeName, MemoryLayout layout) {
        return emitIfAbsent(javaName, Constant.Kind.SEGMENT,
                () -> emitSegmentField(javaName, nativeName, layout));
    }

    public Constant addFunctionDesc(String javaName, FunctionDescriptor desc) {
        return emitIfAbsent(javaName, Constant.Kind.FUNCTION_DESCRIPTOR,
                () -> emitFunctionDescField(javaName, desc));
    }

    public Constant addConstantDesc(String javaName, Class<?> type, Object value) {
        if (type == MemorySegment.class) {
            return emitIfAbsent(javaName, Constant.Kind.SEGMENT,
                    () -> emitConstantSegment(javaName, value));
        } else if (type == MemoryAddress.class) {
            return emitIfAbsent(javaName, Constant.Kind.ADDRESS,
                    () -> emitConstantAddress(javaName, value));
        } else {
            throw new UnsupportedOperationException();
        }
    }

    static class Constant {

        enum Kind {
            LAYOUT(MemoryLayout.class, "$LAYOUT"),
            METHOD_HANDLE(MethodHandle.class, "$MH"),
            VAR_HANDLE(VarHandle.class, "$VH"),
            FUNCTION_DESCRIPTOR(FunctionDescriptor.class, "$FUNC"),
            ADDRESS(MemoryAddress.class, "$ADDR"),
            SEGMENT(MemorySegment.class, "$SEGMENT");

            final Class<?> type;
            final String nameSuffix;

            Kind(Class<?> type, String nameSuffix) {
                this.type = type;
                this.nameSuffix = nameSuffix;
            }

            String fieldName(String javaName) {
                return javaName + nameSuffix;
            }
        }

        private final String className;
        private final String javaName;
        private final Kind kind;

        Constant(String className, String javaName, Kind kind) {
            this.className = className;
            this.javaName = javaName;
            this.kind = kind;
        }

        List<String> getterNameParts() {
            return List.of(className, javaName, kind.nameSuffix);
        }

        String accessExpression() {
            return className + "." + kind.fieldName(javaName);
        }

        Constant emitGetter(ClassSourceBuilder builder, String mods, Function<List<String>, String> getterNameFunc) {
            builder.emitGetter(mods, kind.type, getterNameFunc.apply(getterNameParts()), accessExpression());
            return this;
        }

        Constant emitGetter(ClassSourceBuilder builder, String mods, Function<List<String>, String> getterNameFunc, String symbolName) {
            builder.emitGetter(mods, kind.type, getterNameFunc.apply(getterNameParts()), accessExpression(), true, symbolName);
            return this;
        }

        static final Function<List<String>, String> QUALIFIED_NAME =
                l -> l.stream().skip(1).collect(Collectors.joining());

        static final Function<List<String>, String> JAVA_NAME =
                l -> l.get(1);

        static final Function<List<String>, String> SUFFIX_ONLY =
                l -> l.get(2);
    }

    // private generators

    public Constant emitIfAbsent(String name, Constant.Kind kind, Supplier<Constant> constantFactory) {
        String lookupName = kind.fieldName(name);
        Constant constant = namesGenerated.get(lookupName);
        if (constant == null) {
            constant = constantFactory.get();
            if (constant.kind != kind) {
                throw new AssertionError("Factory return wrong kind of constant; expected: "
                        + kind + "; found: " + constant.kind);
            }
            namesGenerated.put(lookupName, constant);
        }
        return constant;
    }

    private Constant emitMethodHandleField(String javaName, String nativeName, FunctionInfo functionInfo, boolean virtual) {
        Constant functionDesc = addFunctionDesc(javaName, functionInfo.descriptor());
        incrAlign();
        String fieldName = Constant.Kind.METHOD_HANDLE.fieldName(javaName);
        indent();
        append(memberMods() + "MethodHandle ");
        append(fieldName + " = RuntimeHelper.downcallHandle(\n");
        incrAlign();
        indent();
        if (!virtual) {
            append("\"" + nativeName + "\"");
            append(",\n");
            indent();
        }
        append(functionDesc.accessExpression());
        append(", ");
        // isVariadic
        append(functionInfo.isVarargs());
        append("\n");
        decrAlign();
        indent();
        append(");\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.METHOD_HANDLE);
    }

    private Constant emitVarHandleField(String javaName, String nativeName, VarInfo varInfo,
                                      String rootLayoutName, List<String> prefixElementNames) {
        String layoutAccess = rootLayoutName != null ?
                Constant.Kind.LAYOUT.fieldName(rootLayoutName) :
                addLayout(javaName, varInfo.layout()).accessExpression();
        incrAlign();
        String typeName = varInfo.carrier().getName();
        indent();
        String fieldName = Constant.Kind.VAR_HANDLE.fieldName(javaName);
        append(memberMods() + "VarHandle " + fieldName + " = ");
        append(layoutAccess);
        append(".varHandle(");
        String prefix = "";
        if (rootLayoutName != null) {
            for (String prefixElementName : prefixElementNames) {
                append(prefix + "MemoryLayout.PathElement.groupElement(\"" + prefixElementName + "\")");
                prefix = ", ";
            }
            append(prefix + "MemoryLayout.PathElement.groupElement(\"" + nativeName + "\")");
        }
        append(")");
        append(";\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.VAR_HANDLE);
    }

    private Constant emitLayoutField(String javaName, MemoryLayout layout) {
        String fieldName = Constant.Kind.LAYOUT.fieldName(javaName);
        incrAlign();
        indent();
        String layoutClassName = layout.getClass().getSimpleName();
        append(memberMods() + " " + layoutClassName + " " + fieldName + " = ");
        emitLayoutString(layout);
        append(";\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.LAYOUT);
    }

    protected String primitiveLayoutString(ValueLayout layout) {
        return toplevel().rootConstants().resolvePrimitiveLayout(layout).accessExpression();
    }

    private void emitLayoutString(MemoryLayout l) {
        if (l instanceof ValueLayout val) {
            append(primitiveLayoutString(val));
        } else if (l instanceof SequenceLayout seq) {
            append("MemoryLayout.sequenceLayout(");
            if (seq.elementCount().isPresent()) {
                append(seq.elementCount().getAsLong() + ", ");
            }
            emitLayoutString(seq.elementLayout());
            append(")");
        } else if (l instanceof GroupLayout group) {
            if (group.isStruct()) {
                append("MemoryLayout.structLayout(\n");
            } else {
                append("MemoryLayout.unionLayout(\n");
            }
            incrAlign();
            String delim = "";
            for (MemoryLayout e : group.memberLayouts()) {
                append(delim);
                indent();
                emitLayoutString(e);
                delim = ",\n";
            }
            append("\n");
            decrAlign();
            indent();
            append(")");
        } else {
            // padding (or unsupported)
            append("MemoryLayout.paddingLayout(" + l.bitSize() + ")");
        }
        if (l.name().isPresent()) {
            append(".withName(\"" +  l.name().get() + "\")");
        }
    }

    private Constant emitFunctionDescField(String javaName, FunctionDescriptor desc) {
        incrAlign();
        indent();
        String fieldName = Constant.Kind.FUNCTION_DESCRIPTOR.fieldName(javaName);
        final boolean noArgs = desc.argumentLayouts().isEmpty();
        append(memberMods());
        append("FunctionDescriptor ");
        append(fieldName);
        append(" = ");
        if (desc.returnLayout().isPresent()) {
            append("FunctionDescriptor.of(");
            emitLayoutString(desc.returnLayout().get());
            if (!noArgs) {
                append(",");
            }
        } else {
            append("FunctionDescriptor.ofVoid(");
        }
        if (!noArgs) {
            append("\n");
            incrAlign();
            String delim = "";
            for (MemoryLayout e : desc.argumentLayouts()) {
                append(delim);
                indent();
                emitLayoutString(e);
                delim = ",\n";
            }
            append("\n");
            decrAlign();
            indent();
        }
        append(");\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.FUNCTION_DESCRIPTOR);
    }

    private Constant emitConstantSegment(String javaName, Object value) {
        incrAlign();
        indent();
        String fieldName = Constant.Kind.SEGMENT.fieldName(javaName);
        append(memberMods());
        append("MemorySegment ");
        append(fieldName);
        append(" = RuntimeHelper.CONSTANT_ALLOCATOR.allocateUtf8String(\"");
        append(Utils.quote(Objects.toString(value)));
        append("\");\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.SEGMENT);
    }

    private Constant emitConstantAddress(String javaName, Object value) {
        incrAlign();
        indent();
        String fieldName = Constant.Kind.ADDRESS.fieldName(javaName);
        append(memberMods());
        append("MemoryAddress ");
        append(fieldName);
        append(" = MemoryAddress.ofLong(");
        append(((Number)value).longValue());
        append("L);\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.ADDRESS);
    }

    private Constant emitSegmentField(String javaName, String nativeName, MemoryLayout layout) {
        Constant layoutConstant = addLayout(javaName, layout);
        incrAlign();
        indent();
        String fieldName = Constant.Kind.SEGMENT.fieldName(javaName);
        append(memberMods());
        append("MemorySegment ");
        append(fieldName);
        append(" = ");
        append("RuntimeHelper.lookupGlobalVariable(");
        append("\"" + nativeName + "\", ");
        append(layoutConstant.accessExpression());
        append(");\n");
        decrAlign();
        return new Constant(className(), javaName, Constant.Kind.SEGMENT);
    }

    @Override
    protected void emitWithConstantClass(Consumer<ConstantBuilder> constantConsumer) {
        constantConsumer.accept(this);
    }
}
