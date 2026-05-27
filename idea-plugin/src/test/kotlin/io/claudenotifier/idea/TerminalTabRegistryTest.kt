package io.claudenotifier.idea

import org.junit.Assert.*
import org.junit.Test

class TerminalTabRegistryTest {
    @Test
    fun `register adds entry`() {
        val r = TerminalTabRegistry()
        val uuid = r.register(projectName = "foo", projectPath = "/p/foo")
        assertNotNull(uuid)
        val entry = r.lookup(uuid)
        assertNotNull(entry)
        assertEquals("foo", entry!!.projectName)
        assertEquals("/p/foo", entry.projectPath)
    }

    @Test
    fun `lookup missing returns null`() {
        val r = TerminalTabRegistry()
        assertNull(r.lookup("nonexistent"))
    }

    @Test
    fun `unregister removes entry`() {
        val r = TerminalTabRegistry()
        val uuid = r.register(projectName = "x", projectPath = "/x")
        r.unregister(uuid)
        assertNull(r.lookup(uuid))
    }

    @Test
    fun `multiple registrations produce distinct uuids`() {
        val r = TerminalTabRegistry()
        val u1 = r.register("a", "/a")
        val u2 = r.register("a", "/a")
        assertNotEquals(u1, u2)
    }
}
