package com.nox.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFailureCodeTest {
    @Test
    fun chineseTurnLimitHasStableCode() {
        assertEquals(
            AgentFailureCode.CONVERSATION_TURN_LIMIT,
            AgentFailureCode.from("LLM 调用失败: 当前会话模型已回复 100 轮，达到上限")
        )
    }

    @Test
    fun englishTurnLimitHasStableCode() {
        assertEquals(
            AgentFailureCode.CONVERSATION_TURN_LIMIT,
            AgentFailureCode.from("This conversation has reached 100 model turns", "invalid_request_error")
        )
    }

    @Test
    fun unrelatedErrorsKeepOriginalCode() {
        assertEquals("rate_limit_exceeded", AgentFailureCode.from("Too many requests", "rate_limit_exceeded"))
        assertNull(AgentFailureCode.from("Connection timed out"))
        assertNull(AgentFailureCode.from("maximum context length exceeded"))
        assertNull(AgentFailureCode.from(null))
    }

    @Test
    fun explicitTurnLimitCodeSurvivesMissingMessage() {
        assertEquals(
            AgentFailureCode.CONVERSATION_TURN_LIMIT,
            AgentFailureCode.from(null, AgentFailureCode.CONVERSATION_TURN_LIMIT)
        )
    }
}
