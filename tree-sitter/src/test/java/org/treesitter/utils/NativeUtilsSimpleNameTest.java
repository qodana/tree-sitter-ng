package org.treesitter.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeUtilsSimpleNameTest {

    @Test
    void stripsTheDirectoryPrefix() {
        assertEquals("tree-sitter-bash", NativeUtils.simpleLibName("lib/tree-sitter-bash"));
    }

    @Test
    void keepsANameThatHasNoPrefix() {
        assertEquals("tree-sitter", NativeUtils.simpleLibName("tree-sitter"));
    }

    @Test
    void keepsOnlyTheLastSegment() {
        assertEquals("foo", NativeUtils.simpleLibName("a/b/foo"));
    }
}
