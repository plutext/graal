/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.snippets;

// Checkstyle: allow reflection

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.replacements.nodes.BinaryMathIntrinsicNode.BinaryOperation;
import org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.LogHandler;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.util.DirectAnnotationAccess;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.annotate.RestrictHeapAccess;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.code.CodeInfoTable;
import com.oracle.svm.core.deopt.DeoptimizedFrame;
import com.oracle.svm.core.jdk.JDKUtils;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.stack.JavaStackWalker;
import com.oracle.svm.core.stack.StackFrameVisitor;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.VMThreads;
import com.oracle.svm.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.threadlocal.FastThreadLocalObject;
import com.oracle.svm.core.util.VMError;

import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class SnippetRuntime {

    public static final SubstrateForeignCallDescriptor REPORT_TYPE_ASSERTION_ERROR = findForeignCall(SnippetRuntime.class, "reportTypeAssertionError", true, LocationIdentity.any());
    public static final SubstrateForeignCallDescriptor UNREACHED_CODE = findForeignCall(SnippetRuntime.class, "unreachedCode", true, LocationIdentity.any());
    public static final SubstrateForeignCallDescriptor UNRESOLVED = findForeignCall(SnippetRuntime.class, "unresolved", true, LocationIdentity.any());

    public static final SubstrateForeignCallDescriptor UNWIND_EXCEPTION = findForeignCall(SnippetRuntime.class, "unwindException", true, LocationIdentity.any());

    /* Implementation of runtime calls defined in a VM-independent way by Graal. */
    public static final SubstrateForeignCallDescriptor REGISTER_FINALIZER = findForeignCall(SnippetRuntime.class, "registerFinalizer", true);

    /*
     * Graal-defined math functions where we have optimized machine code sequences: We just register
     * the original Math function as the foreign call. The backend will emit the machine code
     * sequence.
     */
    public static final SubstrateForeignCallDescriptor ARITHMETIC_SIN = findForeignCall(UnaryOperation.SIN.foreignCallDescriptor.getName(), Math.class, "sin", true);
    public static final SubstrateForeignCallDescriptor ARITHMETIC_COS = findForeignCall(UnaryOperation.COS.foreignCallDescriptor.getName(), Math.class, "cos", true);
    public static final SubstrateForeignCallDescriptor ARITHMETIC_TAN = findForeignCall(UnaryOperation.TAN.foreignCallDescriptor.getName(), Math.class, "tan", true);
    public static final SubstrateForeignCallDescriptor ARITHMETIC_LOG = findForeignCall(UnaryOperation.LOG.foreignCallDescriptor.getName(), Math.class, "log", true);
    public static final SubstrateForeignCallDescriptor ARITHMETIC_LOG10 = findForeignCall(UnaryOperation.LOG10.foreignCallDescriptor.getName(), Math.class, "log10", true);
    public static final SubstrateForeignCallDescriptor ARITHMETIC_EXP = findForeignCall(UnaryOperation.EXP.foreignCallDescriptor.getName(), Math.class, "exp", true);
    public static final SubstrateForeignCallDescriptor ARITHMETIC_POW = findForeignCall(BinaryOperation.POW.foreignCallDescriptor.getName(), Math.class, "pow", true);

    /*
     * These methods are intrinsified as nodes at first, but can then lowered back to a call. Ensure
     * they are seen as reachable.
     */
    public static final SubstrateForeignCallDescriptor OBJECT_CLONE = findForeignCall(Object.class, "clone", false, LocationIdentity.any());

    public static List<SubstrateForeignCallDescriptor> getRuntimeCalls() {
        List<SubstrateForeignCallDescriptor> result = new ArrayList<>();
        try {
            for (Field field : SnippetRuntime.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == SubstrateForeignCallDescriptor.class) {
                    result.add(((SubstrateForeignCallDescriptor) field.get(null)));
                }
            }
        } catch (IllegalAccessException ex) {
            throw new Error(ex);
        }
        return result;
    }

    public static SubstrateForeignCallDescriptor findForeignCall(Class<?> declaringClass, String methodName, boolean isReexecutable, LocationIdentity... killedLocations) {
        return findForeignCall(methodName, declaringClass, methodName, isReexecutable, killedLocations);
    }

    private static SubstrateForeignCallDescriptor findForeignCall(String descriptorName, Class<?> declaringClass, String methodName, boolean isReexecutable, LocationIdentity... killedLocations) {
        Method foundMethod = null;
        for (Method method : declaringClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                assert foundMethod == null : "found more than one method " + declaringClass.getName() + "." + methodName;
                foundMethod = method;
            }
        }
        assert foundMethod != null : "did not find method " + declaringClass.getName() + "." + methodName;

        /*
         * We cannot annotate methods from the JDK, but all other foreign call targets we want to be
         * annotated for documentation, and to avoid stripping.
         */
        VMError.guarantee(declaringClass.getName().startsWith("java.lang") || DirectAnnotationAccess.isAnnotationPresent(foundMethod, SubstrateForeignCallTarget.class),
                        "Add missing @SubstrateForeignCallTarget to " + declaringClass.getName() + "." + methodName);

        return new SubstrateForeignCallDescriptor(descriptorName, foundMethod, isReexecutable, killedLocations);
    }

    public static class SubstrateForeignCallDescriptor extends ForeignCallDescriptor {

        private final Class<?> declaringClass;
        private final String methodName;
        private final boolean isReexecutable;
        private final LocationIdentity[] killedLocations;

        SubstrateForeignCallDescriptor(String descriptorName, Method method, boolean isReexecutable, LocationIdentity[] killedLocations) {
            super(descriptorName, method.getReturnType(), method.getParameterTypes());
            this.declaringClass = method.getDeclaringClass();
            this.methodName = method.getName();
            this.isReexecutable = isReexecutable;
            this.killedLocations = killedLocations;
        }

        public Class<?> getDeclaringClass() {
            return declaringClass;
        }

        public boolean isReexecutable() {
            return isReexecutable;
        }

        public ResolvedJavaMethod findMethod(MetaAccessProvider metaAccess) {
            for (Method method : declaringClass.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    return metaAccess.lookupJavaMethod(method);
                }
            }
            throw VMError.shouldNotReachHere("method " + methodName + " not found");
        }

        public LocationIdentity[] getKilledLocations() {
            return killedLocations;
        }
    }

    /** Foreign call: {@link #REPORT_TYPE_ASSERTION_ERROR}. */
    @SubstrateForeignCallTarget
    private static void reportTypeAssertionError(byte[] msg, Object object) {
        Log.log().string(msg).string(object == null ? "null" : object.getClass().getName()).newline();
        throw VMError.shouldNotReachHere("type assertion error");
    }

    /** Foreign call: {@link #UNREACHED_CODE}. */
    @SubstrateForeignCallTarget
    private static void unreachedCode() {
        throw VMError.unsupportedFeature("Code that was considered unreachable by closed-world analysis was reached");
    }

    /** Foreign call: {@link #UNRESOLVED}. */
    @SubstrateForeignCallTarget
    private static void unresolved(String sourcePosition) {
        throw VMError.unsupportedFeature("Unresolved element found " + (sourcePosition != null ? sourcePosition : ""));
    }

    /*
     * The stack walking objects must be stateless (no instance fields), because multiple threads
     * can use them simultaneously. All state must be in separate VMThreadLocals.
     */
    public static class ExceptionStackFrameVisitor implements StackFrameVisitor {
        @Uninterruptible(reason = "Set currentException atomically with regard to the safepoint mechanism", calleeMustBe = false)
        @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate when unwinding the stack.")
        @Override
        public boolean visitFrame(Pointer sp, CodePointer ip, DeoptimizedFrame deoptFrame) {
            CodePointer handlerPointer;
            if (deoptFrame != null) {
                /* Deoptimization entry points always have an exception handler. */
                deoptFrame.takeException();
                handlerPointer = ip;

            } else {
                long handler = CodeInfoTable.lookupExceptionOffset(ip);
                if (handler == 0) {
                    /* No handler found in this frame, walk to caller frame. */
                    return true;
                }

                handlerPointer = getExceptionHandlerPointer(ip, sp, handler);
            }

            Throwable exception = currentException.get();
            currentException.set(null);

            StackOverflowCheck.singleton().protectYellowZone();

            KnownIntrinsics.farReturn(exception, sp, handlerPointer);
            /*
             * The intrinsic performs a jump to the specified instruction pointer, so this code is
             * unreachable.
             */
            return false;
        }

        public CodePointer getExceptionHandlerPointer(CodePointer ip, @SuppressWarnings("unused") Pointer sp, long handlerOffset) {
            return (CodePointer) ((UnsignedWord) ip).add(WordFactory.signed(handlerOffset));
        }
    }

    protected static final FastThreadLocalObject<Throwable> currentException = FastThreadLocalFactory.createObject(Throwable.class);

    @Uninterruptible(reason = "Called from uninterruptible callers.", mayBeInlined = true)
    public static boolean isUnwindingForException() {
        return currentException.get() != null;
    }

    @Uninterruptible(reason = "Called from uninterruptible callers.", mayBeInlined = true)
    static boolean exceptionsAreFatal() {
        /*
         * If an exception is thrown while the thread is not in the Java state, most likely
         * something went wrong in our state transition code. We cannot reliably unwind the stack,
         * so exiting quickly is better.
         */
        return SubstrateOptions.MultiThreaded.getValue() && !VMThreads.StatusSupport.isStatusJava();
    }

    /** Foreign call: {@link #UNWIND_EXCEPTION}. */
    @SubstrateForeignCallTarget
    @Uninterruptible(reason = "Set currentException atomically with regard to the safepoint mechanism", calleeMustBe = false)
    @RestrictHeapAccess(access = RestrictHeapAccess.Access.NO_ALLOCATION, reason = "Must not allocate when unwinding the stack.")
    private static void unwindException(Throwable exception, Pointer callerSP, CodePointer callerIP) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();

        if (currentException.get() != null) {
            /*
             * Exception unwinding cannot be called recursively. The most likely reason to end up
             * here is an exception being thrown while walking the stack to find an exception
             * handler.
             */
            Log.log().string("Fatal error: recursion in exception handling: ").string(exception.getClass().getName());
            Log.log().string(" thrown while unwinding ").string(currentException.get().getClass().getName()).newline();
            ImageSingletons.lookup(LogHandler.class).fatalError();
            return;
        }
        currentException.set(exception);

        if (exceptionsAreFatal()) {
            Log.log().string("Fatal error: exception unwind while thread is not in Java state: ").string(exception.getClass().getName());
            ImageSingletons.lookup(LogHandler.class).fatalError();
            return;
        }
        /*
         * callerSP and callerIP identify already the caller of the frame that wants to unwind an
         * exception. So we can start looking for the exception handler immediately in that frame,
         * without skipping any frames in between.
         */
        JavaStackWalker.walkCurrentThread(callerSP, callerIP, ImageSingletons.lookup(ExceptionStackFrameVisitor.class));

        /*
         * The stack walker does not return if an exception handler is found, but instead performs a
         * direct jump to the handler. So when we reach this point, we can just report an unhandled
         * exception.
         */
        reportUnhandledExceptionRaw(exception);
    }

    private static void reportUnhandledExceptionRaw(Throwable exception) {
        Log.log().string(exception.getClass().getName());
        String detail = JDKUtils.getRawMessage(exception);
        if (detail != null) {
            Log.log().string(": ").string(detail);
        }
        Log.log().newline();
        ImageSingletons.lookup(LogHandler.class).fatalError();
    }

    /** Foreign call: {@link #REGISTER_FINALIZER}. */
    @SubstrateForeignCallTarget
    private static void registerFinalizer(@SuppressWarnings("unused") Object obj) {
        // We do not support finalizers, so nothing to do.
    }
}
