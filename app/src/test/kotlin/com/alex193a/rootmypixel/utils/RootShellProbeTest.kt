package com.alex193a.rootmypixel.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellProbeTest {
    @Test
    fun `accepts a direct root identity`() {
        assertTrue(RootShellProbe.isRoot(0, "0"))
    }

    @Test
    fun `accepts a root identity returned by the Shizuku service`() {
        assertTrue(RootShellProbe.isRoot(0, "0\nRMP_EXEC_EXIT:0"))
    }

    @Test
    fun `rejects shell identity and misleading output`() {
        assertFalse(RootShellProbe.isRoot(0, "2000\nRMP_EXEC_EXIT:0"))
        assertFalse(RootShellProbe.isRoot(0, "uid=0(root) gid=0(root)"))
    }

    @Test
    fun `rejects failed local or remote execution`() {
        assertFalse(RootShellProbe.isRoot(1, "0"))
        assertFalse(RootShellProbe.isRoot(0, "0\nRMP_EXEC_EXIT:1"))
        assertFalse(RootShellProbe.isRoot(0, "0\nRMP_EXEC_EXIT:not-a-number"))
    }
}
