/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.interop.access;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.llvm.runtime.except.LLVMPolyglotException;
import com.oracle.truffle.llvm.runtime.interop.access.LLVMInteropAccessNode.AccessLocation;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;

public abstract class LLVMInteropWriteNode extends LLVMNode {

    public static LLVMInteropWriteNode create() {
        return LLVMInteropWriteNodeGen.create();
    }

    public abstract void execute(LLVMInteropType.Structured type, Object foreign, long offset, Object value);

    @Specialization(guards = "type != null")
    void doKnownType(LLVMInteropType.Structured type, Object foreign, long offset, Object value,
                    @Cached LLVMInteropAccessNode access,
                    @CachedLibrary(limit = "3") InteropLibrary interop,
                    @Cached BranchProfile exception) {
        AccessLocation location = access.execute(type, foreign, offset);
        write(interop, location, value, exception);
    }

    @Specialization(guards = "type == null", limit = "3")
    void doUnknownType(@SuppressWarnings("unused") LLVMInteropType.Structured type, Object foreign, long offset, Object value,
                    @Cached GetValueSizeNode getSize,
                    @CachedLibrary("foreign") InteropLibrary interop,
                    @Cached BranchProfile exception) {
        // type unknown: fall back to "array of unknown value type"
        int elementAccessSize = getSize.execute(value);
        AccessLocation location = new AccessLocation(foreign, Long.divideUnsigned(offset, elementAccessSize), null);
        write(interop, location, value, exception);
    }

    private void write(InteropLibrary interop, AccessLocation location, Object value, BranchProfile exception) {
        if (location.identifier instanceof String) {
            String name = (String) location.identifier;
            try {
                interop.writeMember(location.base, name, value);
            } catch (UnsupportedMessageException ex) {
                exception.enter();
                throw new LLVMPolyglotException(this, "Can not write member '%s'.", name);
            } catch (UnknownIdentifierException ex) {
                exception.enter();
                throw new LLVMPolyglotException(this, "Member '%s' not found.", name);
            } catch (UnsupportedTypeException ex) {
                exception.enter();
                throw new LLVMPolyglotException(this, "Wrong type writing to member '%s'.", name);
            }
        } else {
            long idx = (Long) location.identifier;
            try {
                interop.writeArrayElement(location.base, idx, value);
            } catch (InvalidArrayIndexException ex) {
                exception.enter();
                throw new LLVMPolyglotException(this, "Invalid array index %d.", idx);
            } catch (UnsupportedMessageException ex) {
                exception.enter();
                throw new LLVMPolyglotException(this, "Can not write array element %d.", idx);
            } catch (UnsupportedTypeException ex) {
                exception.enter();
                throw new LLVMPolyglotException(this, "Wrong type writing to array element %d.", idx);
            }
        }
    }

    abstract static class GetValueSizeNode extends LLVMNode {

        protected abstract int execute(Object value);

        @Specialization(guards = "valueClass.isInstance(value)")
        int doCached(@SuppressWarnings("unused") Object value,
                        @Cached("value.getClass()") @SuppressWarnings("unused") Class<?> valueClass,
                        @Cached("doGeneric(value)") int cachedSize) {
            return cachedSize;
        }

        @Specialization(replaces = "doCached")
        int doGeneric(Object value) {
            if (value instanceof Byte || value instanceof Boolean) {
                return 1;
            } else if (value instanceof Short || value instanceof Character) {
                return 2;
            } else if (value instanceof Integer || value instanceof Float) {
                return 4;
            } else {
                return 8;
            }
        }
    }
}
