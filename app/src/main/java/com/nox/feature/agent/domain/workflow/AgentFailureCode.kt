package com.nox.feature.agent.domain.workflow

internal object AgentFailureCode {
    const val CONVERSATION_TURN_LIMIT = "conversation_turn_limit"

    private val turnLimit = Regex(
        "(?:当前会话模型已回复\\s*\\d+\\s*轮\\s*[，,]?\\s*达到上限|" +
            "This\\s+conversation\\s+has\\s+reached\\s+\\d+\\s+model\\s+turns\\b)",
        RegexOption.IGNORE_CASE
    )

    fun from(message: String?, fallback: String? = null): String? =
        if (message != null && turnLimit.containsMatchIn(message)) CONVERSATION_TURN_LIMIT else fallback
}
