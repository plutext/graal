/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.api.interop;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.impl.Accessor;
import com.oracle.truffle.api.interop.ForeignAccess.Factory;

class InteropAccessor extends Accessor {

    @Override
    protected InteropSupport interopSupport() {
        return new InteropSupport() {
            @Override
            public boolean canHandle(Object foreignAccess, Object receiver) {
                ForeignAccess fa = (ForeignAccess) foreignAccess;
                TruffleObject obj = (TruffleObject) receiver;
                return fa.canHandle(obj);
            }

            @Override
            public CallTarget canHandleTarget(Object access) {
                ForeignAccess fa = (ForeignAccess) access;
                return fa.checkLanguage();
            }

            @Override
            public boolean isTruffleObject(Object value) {
                return value instanceof TruffleObject;
            }

            @Override
            public Object createEmptyTruffleObject() {
                return EmptyTruffleObject.INSTANCE;
            }
        };
    }

}

class EmptyTruffleObject implements TruffleObject {

    static final EmptyTruffleObject INSTANCE = new EmptyTruffleObject();

    public ForeignAccess getForeignAccess() {
        return ForeignAccess.create(new Factory() {

            public boolean canHandle(TruffleObject obj) {
                return obj == INSTANCE;
            }

            public CallTarget accessMessage(Message tree) {
                return null;
            }
        });
    }

}
