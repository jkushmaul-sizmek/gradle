/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization;

import groovy.lang.GroovyObject;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassRegistry;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Nullable;
import org.gradle.internal.classloader.TransformingClassLoader;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.reflect.PropertyAccessorType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A ClassLoader that takes care of mixing-in some methods and types into various classes, for binary compatibility with older Gradle versions.
 *
 * <p>Mixes GroovyObject into certain types.</p>
 * <p>Generates empty interfaces for certain types that have been removed, but which are baked into the bytecode generated by the Groovy compiler.</p>
 */
public class MixInLegacyTypesClassLoader extends TransformingClassLoader {
    private static final Type GROOVY_OBJECT_TYPE = Type.getType(GroovyObject.class);
    private static final Type META_CLASS_REGISTRY_TYPE = Type.getType(MetaClassRegistry.class);
    private static final Type GROOVY_SYSTEM_TYPE = Type.getType(GroovySystem.class);
    private static final Type META_CLASS_TYPE = Type.getType(MetaClass.class);
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type CLASS_TYPE = Type.getType(Class.class);
    private static final Type STRING_TYPE = Type.getType(String.class);

    private static final String RETURN_OBJECT_FROM_OBJECT_STRING_OBJECT = Type.getMethodDescriptor(OBJECT_TYPE, OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String RETURN_OBJECT_FROM_STRING_OBJECT = Type.getMethodDescriptor(OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String RETURN_OBJECT_FROM_STRING = Type.getMethodDescriptor(OBJECT_TYPE, STRING_TYPE);
    private static final String RETURN_OBJECT_FROM_OBJECT_STRING = Type.getMethodDescriptor(OBJECT_TYPE, OBJECT_TYPE, STRING_TYPE);
    private static final String RETURN_VOID_FROM_OBJECT_STRING_OBJECT = Type.getMethodDescriptor(Type.VOID_TYPE, OBJECT_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String RETURN_VOID_FROM_STRING_OBJECT = Type.getMethodDescriptor(Type.VOID_TYPE, STRING_TYPE, OBJECT_TYPE);
    private static final String RETURN_META_CLASS_REGISTRY = Type.getMethodDescriptor(META_CLASS_REGISTRY_TYPE);
    private static final String RETURN_META_CLASS_FROM_CLASS = Type.getMethodDescriptor(META_CLASS_TYPE, CLASS_TYPE);
    private static final String RETURN_META_CLASS = Type.getMethodDescriptor(META_CLASS_TYPE);
    private static final String RETURN_CLASS = Type.getMethodDescriptor(CLASS_TYPE);

    private static final String META_CLASS_FIELD = "__meta_class__";

    private LegacyTypesSupport legacyTypesSupport;

    static {
        /*
         * This classloader is thread-safe and TransformingClassLoader is parallel capable,
         * so register as such to reduce contention when running multithreaded builds
        */
        ClassLoader.registerAsParallelCapable();
    }

    public MixInLegacyTypesClassLoader(ClassLoader parent, ClassPath classPath, LegacyTypesSupport legacyTypesSupport) {
        super(parent, classPath);
        this.legacyTypesSupport = legacyTypesSupport;
    }

    @Nullable
    @Override
    protected byte[] generateMissingClass(String name) {
        if (!legacyTypesSupport.getSyntheticClasses().contains(name)) {
            return null;
        }
        return legacyTypesSupport.generateSyntheticClass(name);
    }

    @Override
    protected boolean shouldTransform(String className) {
        return legacyTypesSupport.getClassesToMixInGroovyObject().contains(className) || legacyTypesSupport.getSyntheticClasses().contains(className);
    }

    @Override
    protected byte[] transform(String className, byte[] bytes) {
        ClassReader classReader = new ClassReader(bytes);
        ClassWriter classWriter = new ClassWriter(0);
        classReader.accept(new TransformingAdapter(classWriter), 0);
        bytes = classWriter.toByteArray();
        return bytes;
    }

    private static class TransformingAdapter extends ClassVisitor {
        private static final int PUBLIC_STATIC_FINAL = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
        private String className;
        /**
         * We only add getters for `public static final String` constants. This is because in
         * the converted classes only contain these kinds of constants.
         */
        private Map<String, String> missingStaticStringConstantGetters = new HashMap<String, String>();
        private Set<String> booleanGetGetters = new HashSet<String>();
        private Set<String> booleanFields = new HashSet<String>();
        private Set<String> booleanIsGetters = new HashSet<String>();

        TransformingAdapter(ClassVisitor cv) {
            super(Opcodes.ASM6, cv);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;

            Set<String> interfaceNames = new LinkedHashSet<String>(Arrays.asList(interfaces));
            interfaceNames.add(GROOVY_OBJECT_TYPE.getInternalName());
            cv.visit(version, access, name, signature, superName, interfaceNames.toArray(new String[0]));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            if (((access & PUBLIC_STATIC_FINAL) == PUBLIC_STATIC_FINAL) && Type.getDescriptor(String.class).equals(desc)) {
                missingStaticStringConstantGetters.put("get" + name, (String) value);
            }
            if (((access & Opcodes.ACC_PRIVATE) > 0) && !isStatic(access) && (Type.getDescriptor(boolean.class).equals(desc))) {
                booleanFields.add(name);
            }
            return super.visitField(access, name, desc, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (missingStaticStringConstantGetters.containsKey(name)) {
                missingStaticStringConstantGetters.remove(name);
            }
            if (((access & Opcodes.ACC_PUBLIC) > 0) && !isStatic(access) && Type.getMethodDescriptor(Type.BOOLEAN_TYPE).equals(desc)) {
                PropertyAccessorType accessorType = PropertyAccessorType.fromName(name);
                if (accessorType != null) {
                    String propertyName = accessorType.propertyNameFor(name);
                    if (accessorType == PropertyAccessorType.IS_GETTER) {
                        booleanIsGetters.add(propertyName);
                    } else if (accessorType == PropertyAccessorType.GET_GETTER) {
                        booleanGetGetters.add(propertyName);
                    }
                }
            }
            return super.visitMethod(access, name, desc, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            addMetaClassField();
            addGetMetaClass();
            addSetMetaClass();
            addGetProperty();
            addSetProperty();
            addInvokeMethod();
            addStaticStringConstantGetters();
            addBooleanGetGetters();
            cv.visitEnd();
        }

        private boolean isStatic(int access) {
            return (access & Opcodes.ACC_STATIC) > 0;
        }

        private void addMetaClassField() {
            cv.visitField(Opcodes.ACC_PRIVATE, META_CLASS_FIELD, META_CLASS_TYPE.getDescriptor(), null, null);
        }

        private void addGetProperty() {
            MethodVisitor methodVisitor = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getProperty", RETURN_OBJECT_FROM_STRING, null, null);
            methodVisitor.visitCode();

            // this.getMetaClass()
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "getMetaClass", RETURN_META_CLASS, false);

            // getProperty(this, name)
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, META_CLASS_TYPE.getInternalName(), "getProperty", RETURN_OBJECT_FROM_OBJECT_STRING, true);

            // return
            methodVisitor.visitInsn(Opcodes.ARETURN);
            methodVisitor.visitMaxs(3, 2);
            methodVisitor.visitEnd();
        }

        private void addSetProperty() {
            MethodVisitor methodVisitor = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "setProperty",  RETURN_VOID_FROM_STRING_OBJECT, null, null);
            methodVisitor.visitCode();

            // this.getMetaClass()
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "getMetaClass", RETURN_META_CLASS, false);

            // setProperty(this, name, value)
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, META_CLASS_TYPE.getInternalName(), "setProperty",  RETURN_VOID_FROM_OBJECT_STRING_OBJECT, true);

            methodVisitor.visitInsn(Opcodes.RETURN);
            methodVisitor.visitMaxs(4, 3);
            methodVisitor.visitEnd();
        }

        private void addInvokeMethod() {
            MethodVisitor methodVisitor = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "invokeMethod", RETURN_OBJECT_FROM_STRING_OBJECT, null, null);
            methodVisitor.visitCode();

            // this.getMetaClass()
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "getMetaClass", RETURN_META_CLASS, false);

            // invokeMethod(this, name, args)
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 2);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, META_CLASS_TYPE.getInternalName(), "invokeMethod",  RETURN_OBJECT_FROM_OBJECT_STRING_OBJECT, true);

            // return
            methodVisitor.visitInsn(Opcodes.ARETURN);
            methodVisitor.visitMaxs(4, 3);
            methodVisitor.visitEnd();
        }

        private void addGetMetaClass() {
            Label lookup = new Label();

            MethodVisitor methodVisitor = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getMetaClass", RETURN_META_CLASS, null, null);
            methodVisitor.visitCode();

            // if (this.metaClass != null) { return this.metaClass; }
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, META_CLASS_FIELD, META_CLASS_TYPE.getDescriptor());
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitJumpInsn(Opcodes.IFNULL, lookup);
            methodVisitor.visitInsn(Opcodes.ARETURN);

            methodVisitor.visitLabel(lookup);
            methodVisitor.visitFrame(Opcodes.F_NEW, 1, new Object[]{className}, 1, new Object[]{META_CLASS_TYPE.getInternalName()});
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0); // for storing to field

            // GroovySystem.getMetaClassRegistry()
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, GROOVY_SYSTEM_TYPE.getInternalName(), "getMetaClassRegistry", RETURN_META_CLASS_REGISTRY, false);

            // this.getClass()
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OBJECT_TYPE.getInternalName(), "getClass", RETURN_CLASS, false);

            // getMetaClass(..)
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, META_CLASS_REGISTRY_TYPE.getInternalName(), "getMetaClass", RETURN_META_CLASS_FROM_CLASS, true);

            // this.metaClass = <value>
            methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, META_CLASS_FIELD, META_CLASS_TYPE.getDescriptor());

            // return this.metaClass
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitFieldInsn(Opcodes.GETFIELD, className, META_CLASS_FIELD, META_CLASS_TYPE.getDescriptor());

            methodVisitor.visitInsn(Opcodes.ARETURN);
            methodVisitor.visitMaxs(4, 1);
            methodVisitor.visitEnd();
        }

        private void addSetMetaClass() {
            MethodVisitor methodVisitor = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "setMetaClass", Type.getMethodDescriptor(Type.VOID_TYPE, META_CLASS_TYPE), null, null);
            methodVisitor.visitCode();

            // this.metaClass = <value>
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, 1);
            methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, className, META_CLASS_FIELD, META_CLASS_TYPE.getDescriptor());

            methodVisitor.visitInsn(Opcodes.RETURN);
            methodVisitor.visitMaxs(2, 2);
            methodVisitor.visitEnd();
        }

        private void addStaticStringConstantGetters() {
            for (Map.Entry<String, String> constant : missingStaticStringConstantGetters.entrySet()) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    constant.getKey(),
                    Type.getMethodDescriptor(Type.getType(String.class)), null, null);
                mv.visitCode();
                mv.visitLdcInsn(constant.getValue());
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();
            }
        }

        private void addBooleanGetGetters() {
            Collection<String> accessibleBooleanFieldsWithoutGetGetters = new HashSet<String>();
            accessibleBooleanFieldsWithoutGetGetters.addAll(booleanFields);
            accessibleBooleanFieldsWithoutGetGetters.retainAll(booleanIsGetters);
            accessibleBooleanFieldsWithoutGetGetters.removeAll(booleanGetGetters);

            for (String booleanField : accessibleBooleanFieldsWithoutGetGetters) {
                addBooleanGetGetter(booleanField);
            }
        }

        private void addBooleanGetGetter(String booleanField) {
            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "get" + StringUtils.capitalize(booleanField), "()Z", null, null);
            mv.visitCode();
            Label l0 = new Label();
            mv.visitLabel(l0);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, className, booleanField, "Z");
            mv.visitInsn(Opcodes.IRETURN);
            Label l1 = new Label();
            mv.visitLabel(l1);
            mv.visitLocalVariable("this", "L" + className + ";", null, l0, l1, 0);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
    }
}
