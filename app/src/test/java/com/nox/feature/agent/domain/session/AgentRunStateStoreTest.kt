package com.nox.feature.agent.domain.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunStateStoreTest {
    @Test
    fun failureMakesRunIdleAndRetainsErrorCode() = runBlocking {
        val store = AgentRunStateStore()
        store.started("session", "run-1")
        assertTrue(store.snapshot("session")!!.running)
        assertTrue(store.finished("session", "error", "conversation_turn_limit", "run-1"))
        val snapshot = store.awaitIdle("session", 100)!!
        assertFalse(snapshot.running)
        assertEquals("error", snapshot.status)
        assertEquals("conversation_turn_limit", snapshot.error)
    }

    @Test
    fun staleRunCannotFinishReplacementRun() {
        val store = AgentRunStateStore()
        store.started("session", "run-1")
        store.started("session", "run-2")
        assertFalse(store.finished("session", "cancelled", null, "run-1"))
        assertTrue(store.snapshot("session")!!.running)
        assertEquals("run-2", store.snapshot("session")!!.runId)
        assertTrue(store.finished("session", "success", null, "run-2"))
        assertFalse(store.snapshot("session")!!.running)
    }

    @Test
    fun cancellationDoesNotRetainPreviousFailure() {
        val store = AgentRunStateStore()
        store.started("session", "run-1")
        store.finished("session", "error", "conversation_turn_limit", "run-1")
        store.started("session", "run-2")
        assertTrue(store.finished("session", "cancelled", null, "run-2"))
        assertEquals("cancelled", store.snapshot("session")!!.status)
        assertNull(store.snapshot("session")!!.error)
    }

    @Test
    fun staleCompletionDoesNotEmitFinishedEvent() = runBlocking {
        val store = AgentRunStateStore()
        val events = async(start = CoroutineStart.UNDISPATCHED) {
            store.events.take(3).toList()
        }
        store.started("session", "run-1")
        store.started("session", "run-2")
        assertFalse(store.finished("session", "error", "conversation_turn_limit", "run-1"))
        assertTrue(store.finished("session", "success", null, "run-2"))
        val received = events.await()
        assertEquals(2, received.count { it is AgentRunEvent.Started })
        val finished = received.filterIsInstance<AgentRunEvent.Finished>().single()
        assertEquals("success", finished.status)
        assertNull(finished.error)
    }

    @Test
    fun independentSessionsKeepTheirOwnRunState() {
        val store = AgentRunStateStore()
        store.started("first", "run-1")
        store.started("second", "run-2")
        store.finished("first", "success", null, "run-1")
        assertFalse(store.snapshot("first")!!.running)
        assertTrue(store.snapshot("second")!!.running)
    }
}
