package com.example

import com.example.domain.privilege.CommandExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExecutorTest {

    @Test
    fun testExecuteSimpleCommandSuccess() = runBlocking {
        val result = CommandExecutor.execute("echo 'Hello Network'", timeoutSeconds = 3)
        assertTrue(result.isSuccess)
        assertEquals(0, result.exitCode)
        assertEquals("Hello Network", result.stdout)
        assertFalse(result.timedOut)
        assertTrue(result.executionTimeMs >= 0)
    }

    @Test
    fun testExecuteFailingCommandReportsExitCode() = runBlocking {
        val result = CommandExecutor.execute("exit 42", timeoutSeconds = 3)
        assertFalse(result.isSuccess)
        assertEquals(42, result.exitCode)
    }

    @Test
    fun testExecuteCommandTimeout() = runBlocking {
        val result = CommandExecutor.execute("sleep 5", timeoutSeconds = 1)
        assertTrue(result.timedOut)
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("timed out"))
    }
}
