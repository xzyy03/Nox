package com.nox.feature.plugin.runtime

import android.content.Context
import com.nox.R
import com.nox.feature.agent.data.local.dao.AgentMessageDao
import com.nox.feature.agent.data.local.dao.ChatSessionDao
import com.nox.feature.agent.data.local.entity.ChatSessionEntity
import com.nox.feature.agent.domain.model.ReasoningEffort
import com.nox.feature.agent.domain.session.AgentChatRequest
import com.nox.feature.agent.domain.session.AgentChatRequestBus
import com.nox.feature.agent.domain.session.AgentRunStateStore
import com.nox.feature.agent.domain.session.SessionUseCase
import com.nox.feature.plugin.domain.InstalledPlugin
import com.nox.feature.plugin.domain.PluginHostException
import com.nox.feature.plugin.domain.PluginManager
import com.nox.feature.settings.data.repository.DefaultModelSettingsRepository
import com.nox.feature.settings.data.repository.ModelReasoningEffortRepository
import com.nox.feature.settings.domain.model.AIProviderConfig
import com.nox.feature.settings.domain.model.ProviderKey
import com.nox.feature.settings.domain.model.ProviderType
import com.nox.feature.settings.domain.repository.AIProviderRepository
import com.nox.feature.workspace.data.repository.WorkspaceRepository
import com.nox.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginChatServiceTest {
    private val context = mockk<Context>()
    private val pluginManager = mockk<PluginManager>(relaxed = true)
    private val workspaceRepository = mockk<WorkspaceRepository>()
    private val chatSessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val agentMessageDao = mockk<AgentMessageDao>(relaxed = true)
    private val defaults = mockk<DefaultModelSettingsRepository>()
    private val efforts = mockk<ModelReasoningEffortRepository>()
    private val providers = mockk<AIProviderRepository>()
    private val requestBus = AgentChatRequestBus()
    private val runStateStore = AgentRunStateStore()
    private val persisted = mutableListOf<ChatSessionEntity>()
    private val provider = AIProviderConfig(
        id = "explicit-provider",
        name = "Explicit provider",
        type = ProviderType.OPENAI,
        apiKey = "test-only-secret-key",
        baseUrl = "https://provider.invalid",
        defaultModel = "provider-default",
        selectedModel = "provider-selected",
        models = listOf("explicit-model")
    )
    private lateinit var service: PluginChatService

    @Before
    fun setUp() {
        val plugin = mockk<InstalledPlugin>()
        every { plugin.id } returns "test.plugin"
        every { plugin.isActive } returns true
        every { pluginManager.plugins } returns MutableStateFlow(listOf(plugin))
        every { workspaceRepository.current } returns MutableStateFlow<Workspace?>(
            Workspace(name = "test", path = "/workspace/test")
        )
        every { context.getString(R.string.chat_new_session_btn) } returns "New session"
        coEvery { defaults.getDefaultProviderId() } returns "default-provider"
        coEvery { defaults.getDefaultModel() } returns "default-model"
        every { efforts.get(any(), any()) } returns null
        coEvery { providers.getProviderById(provider.id) } returns provider
        coEvery { chatSessionDao.upsert(any()) } answers {
            persisted.add(firstArg())
            Unit
        }
        service = PluginChatService(
            context = context,
            pluginManager = pluginManager,
            workspaceRepository = workspaceRepository,
            sessionUseCase = SessionUseCase(chatSessionDao, agentMessageDao),
            chatSessionDao = chatSessionDao,
            agentMessageDao = agentMessageDao,
            defaultModelSettingsRepository = defaults,
            modelReasoningEffortRepository = efforts,
            providerRepository = providers,
            requestBus = requestBus,
            runStateStore = runStateStore,
            toolPermissionManager = mockk(),
            askUserQuestionManager = mockk(),
            planApprovalManager = mockk()
        )
    }

    @Test
    fun explicitSelectionOverridesDefaultsAndRoundTripsEffort() = runTest {
        val result = create(explicitParams("reasoningEffort" to JsonPrimitive(" HiGh ")))
        val saved = persisted.single()
        assertEquals(provider.id, saved.providerId)
        assertEquals("explicit-model", saved.model)
        assertEquals("HIGH", saved.reasoningEffort)
        assertEquals("/workspace/test", saved.workspacePath)
        assertEquals("BUILD", saved.mode)
        assertEquals(provider.id, result.getValue("providerId").jsonPrimitive.content)
        assertEquals("explicit-model", result.getValue("model").jsonPrimitive.content)
        assertEquals("high", result.getValue("reasoningEffort").jsonPrimitive.content)
        assertFalse(result.toString().contains(provider.apiKey))
        assertFalse(result.containsKey("apiKey"))
        assertFalse(saved.fastMode)
        coVerify(exactly = 0) { defaults.getDefaultProviderId() }
        coVerify(exactly = 0) { defaults.getDefaultModel() }
        verify(exactly = 0) { efforts.get(any(), any()) }
        val roundTripped = create(explicitParams("reasoningEffort" to result.getValue("reasoningEffort")))
        assertEquals(result.getValue("reasoningEffort"), roundTripped.getValue("reasoningEffort"))
    }

    @Test
    fun absentOverridesKeepDefaultsAndRememberedEffort() = runTest {
        every { efforts.get("default-provider", "default-model") } returns "XHIGH"
        val result = create(buildJsonObject {})
        assertEquals("default-provider", persisted.single().providerId)
        assertEquals("default-model", persisted.single().model)
        assertEquals("XHIGH", persisted.single().reasoningEffort)
        assertEquals("xhigh", result.getValue("reasoningEffort").jsonPrimitive.content)
        assertEquals("New session", persisted.single().title)
        coVerify(exactly = 0) { providers.getProviderById(any()) }
    }

    @Test
    fun absentDefaultsKeepUnboundSessionAndMediumEffort() = runTest {
        coEvery { defaults.getDefaultProviderId() } returns " "
        coEvery { defaults.getDefaultModel() } returns ""
        val result = create(buildJsonObject {})
        assertNull(persisted.single().providerId)
        assertNull(persisted.single().model)
        assertEquals("MEDIUM", persisted.single().reasoningEffort)
        assertFalse(result.containsKey("providerId"))
        assertFalse(result.containsKey("model"))
        verify(exactly = 0) { efforts.get(any(), any()) }
    }

    @Test
    fun explicitModelUsesRememberedEffortThenMediumFallback() = runTest {
        every { efforts.get(provider.id, "explicit-model") } returns "MAX"
        create(explicitParams())
        assertEquals("MAX", persisted.last().reasoningEffort)
        every { efforts.get(provider.id, "explicit-model") } returns null
        create(explicitParams())
        assertEquals("MEDIUM", persisted.last().reasoningEffort)
    }

    @Test
    fun effortAloneOverridesDefaultForEveryLevel() = runTest {
        ReasoningEffort.entries.forEach { effort ->
            val result = create(buildJsonObject { put("reasoningEffort", effort.name.lowercase()) })
            assertEquals(effort.name, persisted.last().reasoningEffort)
            assertEquals(effort.name.lowercase(), result.getValue("reasoningEffort").jsonPrimitive.content)
        }
        verify(exactly = 0) { efforts.get(any(), any()) }
    }

    @Test
    fun acceptsTrimmedConfiguredDefaultAndSelectedModels() = runTest {
        coEvery { providers.getProviderById(provider.id) } returns provider.copy(models = listOf(" explicit-model "))
        listOf("explicit-model", "provider-default", "provider-selected").forEach { model ->
            create(buildJsonObject {
                put("providerId", " ${provider.id} ")
                put("model", " $model ")
            })
            assertEquals(provider.id, persisted.last().providerId)
            assertEquals(model, persisted.last().model)
        }
    }

    @Test
    fun rejectsUnpairedProviderOrModelBeforePersistence() = runTest {
        listOf("providerId", "model").forEach { key ->
            assertRejected(buildJsonObject { put(key, "explicit") }, "PARAM_INVALID")
        }
        coVerify(exactly = 0) { providers.getProviderById(any()) }
    }

    @Test
    fun rejectsInvalidTypesAndBlankOverrides() = runTest {
        val invalid = listOf(
            JsonNull, JsonPrimitive(123), JsonPrimitive(true), JsonArray(emptyList()),
            buildJsonObject {}, JsonPrimitive(""), JsonPrimitive(" \n\t")
        )
        listOf("providerId", "model", "reasoningEffort").forEach { key ->
            invalid.forEach { assertRejected(explicitParams(key to it), "PARAM_INVALID") }
        }
        assertRejected(explicitParams("reasoningEffort" to JsonPrimitive("unknown")), "PARAM_INVALID")
        coVerify(exactly = 0) { providers.getProviderById(any()) }
    }

    @Test
    fun rejectsMissingDisabledKeylessProviderAndUnknownModel() = runTest {
        coEvery { providers.getProviderById(provider.id) } returns null
        assertRejected(explicitParams(), "PROVIDER_NOT_FOUND")
        coEvery { providers.getProviderById(provider.id) } returns provider.copy(isEnabled = false)
        assertRejected(explicitParams(), "PROVIDER_DISABLED")
        coEvery { providers.getProviderById(provider.id) } returns provider.copy(apiKey = " ")
        assertRejected(explicitParams(), "PROVIDER_NO_KEY")
        coEvery { providers.getProviderById(provider.id) } returns provider
        assertRejected(explicitParams("model" to JsonPrimitive("default-model")), "MODEL_NOT_AVAILABLE")
    }

    @Test
    fun acceptsMultiKeyProviderWithoutSingleKey() = runTest {
        coEvery { providers.getProviderById(provider.id) } returns provider.copy(
            apiKey = "", multiKeyEnabled = true, apiKeys = listOf(ProviderKey("test-only-multi-key"))
        )
        val result = create(explicitParams())
        assertEquals(provider.id, persisted.single().providerId)
        assertFalse(result.toString().contains("test-only-multi-key"))
    }

    @Test
    fun sendMessageNeedsAttachedConsumerAndUsesNormalQueue() = runTest {
        create(explicitParams())
        val session = persisted.single()
        coEvery { chatSessionDao.getById(session.id) } returns session
        val params = buildJsonObject { put("sessionId", session.id); put("text", "continue task") }
        try {
            service.sendMessage("test.plugin", params)
            throw AssertionError("Expected CHAT_UI_UNAVAILABLE")
        } catch (error: PluginHostException) {
            assertEquals("CHAT_UI_UNAVAILABLE", error.code)
        }
        requestBus.attach("chat-ui")
        val result = service.sendMessage("test.plugin", params).jsonObject
        assertEquals(JsonPrimitive(true), result["accepted"])
        assertEquals(AgentChatRequest.Send(session.id, "continue task", session.workspacePath), requestBus.receive())
        requestBus.detach("chat-ui")
    }

    @Test
    fun runStateExposesStableCodeAndCancellationClearsIt() = runTest {
        create(explicitParams())
        val session = persisted.single()
        coEvery { chatSessionDao.getById(session.id) } returns session
        val params = buildJsonObject { put("sessionId", session.id) }
        runStateStore.started(session.id, "run-1")
        runStateStore.finished(session.id, "error", "conversation_turn_limit", "run-1")
        val failed = service.getRunState("test.plugin", params).jsonObject.getValue("run").jsonObject
        assertEquals(JsonPrimitive("conversation_turn_limit"), failed["errorCode"])
        assertFalse(failed.containsKey("error"))
        runStateStore.started(session.id, "run-2")
        runStateStore.finished(session.id, "cancelled", null, "run-2")
        val cancelled = service.getRunState("test.plugin", params).jsonObject.getValue("run").jsonObject
        assertEquals(JsonPrimitive("cancelled"), cancelled["status"])
        assertFalse(cancelled.containsKey("errorCode"))
        assertEquals(JsonPrimitive(false), cancelled["running"])
    }

    private fun explicitParams(vararg overrides: Pair<String, JsonElement>): JsonObject =
        JsonObject(buildJsonObject {
            put("providerId", provider.id)
            put("model", "explicit-model")
        } + overrides.toMap())

    private suspend fun create(params: JsonObject): JsonObject =
        service.createSession("test.plugin", params).jsonObject.getValue("session").jsonObject

    private suspend fun assertRejected(params: JsonObject, code: String): PluginHostException {
        val before = persisted.size
        val error = try {
            create(params)
            throw AssertionError("Expected $code")
        } catch (error: PluginHostException) {
            error
        }
        assertEquals(code, error.code)
        assertEquals(before, persisted.size)
        assertTrue(error.message.isNotBlank())
        assertFalse(error.message.contains(provider.apiKey))
        return error
    }
}
