/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.configure.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.oracle.svm.configure.json.JsonPrintable;
import com.oracle.svm.configure.json.JsonWriter;

public class ProxyConfiguration implements JsonPrintable {
    private final Set<Set<String>> interfaceSets = new HashSet<>();

    public void add(Set<String> interfaceSet) {
        interfaceSets.add(interfaceSet);
    }

    @Override
    public void printJson(JsonWriter writer) throws IOException {
        List<String[]> sets = new ArrayList<>(interfaceSets.size());
        for (Set<String> set : interfaceSets) {
            String[] array = set.toArray(new String[0]);
            Arrays.sort(array);
            sets.add(array);
        }
        sets.sort((a, b) -> {
            int c = 0;
            for (int i = 0; c == 0 && i < a.length && i < b.length; i++) {
                c = a[i].compareTo(b[i]);
            }
            return (c != 0) ? c : (a.length - b.length);
        });

        writer.append('[');
        writer.indent();
        String prefix = "";
        for (String[] set : sets) {
            writer.append(prefix).newline();
            char typePrefix = '[';
            for (String type : set) {
                writer.append(typePrefix).quote(type);
                typePrefix = ',';
            }
            writer.append(']');
            prefix = ",";
        }
        writer.unindent().newline();
        writer.append(']').newline();
    }
}
