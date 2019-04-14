/*
 * Copyright (c) 2017, 2019, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.parser.scanner;

import java.util.Arrays;

import com.oracle.truffle.llvm.runtime.except.LLVMParserException;

final class RecordBuffer {

    private static final int INITIAL_BUFFER_SIZE = 256;

    private long[] opBuffer = new long[INITIAL_BUFFER_SIZE];

    private int size = 0;

    void addOpNoCheck(long op) {
        opBuffer[size++] = op;
    }

    void addOp(long op) {
        ensureFits(1);
        addOpNoCheck(op);
    }

    void ensureFits(long numOfAdditionalOps) {
        if (size >= opBuffer.length - numOfAdditionalOps) {
            opBuffer = Arrays.copyOf(opBuffer, opBuffer.length + ((int) numOfAdditionalOps * 2));
        }
    }

    long getId() {
        if (size <= 0) {
            throw new LLVMParserException("Record Id not set!");
        }
        return opBuffer[0];
    }

    long[] getOps() {
        return Arrays.copyOfRange(opBuffer, 1, size);
    }

    void invalidate() {
        size = 0;
    }
}
