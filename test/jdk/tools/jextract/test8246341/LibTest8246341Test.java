/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.foreign.SegmentAllocator;
import org.testng.annotations.Test;
import test.jextract.test8246341.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static test.jextract.test8246341.test8246341_h.*;
import static jdk.incubator.foreign.CLinker.*;

/*
 * @test id=classes
 * @bug 8246341
 * @summary jextract should generate Cpointer utilities class
 * @library ..
 * @modules jdk.incubator.jextract
 * @run driver JtregJextract -l Test8246341 -t test.jextract.test8246341 -- test8246341.h
 * @run testng/othervm --enable-native-access=jdk.incubator.jextract,ALL-UNNAMED LibTest8246341Test
 */
/*
 * @test id=sources
 * @bug 8246341
 * @summary jextract should generate Cpointer utilities class
 * @library ..
 * @modules jdk.incubator.jextract
 * @run driver JtregJextractSources -l Test8246341 -t test.jextract.test8246341 -- test8246341.h
 * @run testng/othervm --enable-native-access=jdk.incubator.jextract,ALL-UNNAMED LibTest8246341Test
 */
public class LibTest8246341Test {
    @Test
    public void testPointerArray() {
        boolean[] callbackCalled = new boolean[1];
        try (ResourceScope scope = ResourceScope.newConfinedScope()) {
            var callback = func$callback.allocate((argc, argv) -> {
                callbackCalled[0] = true;
                var addr = MemorySegment.ofAddress(argv, C_POINTER.byteSize() * argc, scope);
                assertEquals(argc, 4);
                assertEquals(addr.get(C_POINTER, 0).getUtf8String(0), "java");
                assertEquals(addr.get(C_POINTER, C_POINTER.byteSize() * 1).getUtf8String(0), "python");
                assertEquals(addr.get(C_POINTER, C_POINTER.byteSize() * 2).getUtf8String(0), "javascript");
                assertEquals(addr.get(C_POINTER, C_POINTER.byteSize() * 3).getUtf8String(0), "c++");
            }, scope);
            func(callback);
        }
        assertTrue(callbackCalled[0]);
    }

    @Test
    public void testPointerAllocate() {
        try (var scope = ResourceScope.newConfinedScope()) {
            var allocator = SegmentAllocator.newNativeArena(C_POINTER.byteSize(), scope);
            var addr = allocator.allocate(C_POINTER);
            addr.set(C_POINTER, 0, MemoryAddress.NULL);
            fillin(addr);
            assertEquals(addr.get(C_POINTER, 0).getUtf8String(0), "hello world");
        }
    }
}
