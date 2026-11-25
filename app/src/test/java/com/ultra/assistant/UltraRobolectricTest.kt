package com.ultra.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ultra.assistant.brain.IntentCategory
import com.ultra.assistant.brain.IntentEngine
import com.ultra.assistant.brain.OfflineAssistantEngine
import com.ultra.assistant.brain.PlannedToolCall
import com.ultra.assistant.brain.ReasoningDecision
import com.ultra.assistant.brain.TaskPlanner
import com.ultra.assistant.control.BrowserController
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.firebase.CloudSyncRepository
import com.ultra.assistant.memory.MemoryManager
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.tools.TaskExecutor
import com.ultra.assistant.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UltraRobolectricTest {

    @Test
    fun `main activity launches cleanly without crashing`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `app name resource matches ULTRA AI identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ULTRA AI", context.getString(R.string.app_name))
    }

    @Test
    fun `tool registry validates all 21 tools and rejects bad params`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val registry = ToolRegistry(context, memoryMgr, settingsRepo)

        assertEquals(21, registry.allTools().size)

        val openApp = registry.getTool("open_app")!!
        assertNotNull(openApp.validateArgs(emptyMap()))
        assertNull(openApp.validateArgs(mapOf("appName" to "Settings")))

        val clickTool = registry.getTool("click_ui_element")!!
        assertNotNull(clickTool.validateArgs(emptyMap()))
        assertNull(clickTool.validateArgs(mapOf("targetText" to "Allow")))

        val reminderTool = registry.getTool("create_reminder")!!
        assertNotNull(reminderTool.validateArgs(mapOf("title" to "Call", "delayMinutes" to "abc")))
        assertNull(reminderTool.validateArgs(mapOf("title" to "Call", "delayMinutes" to "15")))

        val youtubeTool = registry.getTool("search_youtube")!!
        assertNotNull(youtubeTool.validateArgs(emptyMap()))
        assertNull(youtubeTool.validateArgs(mapOf("query" to "Kotlin Compose")))

        val volumeTool = registry.getTool("control_media_volume")!!
        assertNotNull(volumeTool.validateArgs(emptyMap()))
        assertNull(volumeTool.validateArgs(mapOf("volumeAction" to "up")))

        val deviceSettingsTool = registry.getTool("open_device_settings")!!
        assertNotNull(deviceSettingsTool.validateArgs(emptyMap()))
        assertNull(deviceSettingsTool.validateArgs(mapOf("target" to "wifi")))

        val callTool = registry.getTool("make_phone_call")!!
        assertNotNull(callTool.validateArgs(emptyMap()))
        assertNull(callTool.validateArgs(mapOf("target" to "Mom")))

        val navTool = registry.getTool("navigate_system")!!
        assertNotNull(navTool.validateArgs(emptyMap()))
        assertNull(navTool.validateArgs(mapOf("action" to "home")))

        val scrollTool = registry.getTool("scroll_screen")!!
        assertNull(scrollTool.validateArgs(mapOf("direction" to "left")))
        assertNull(scrollTool.validateArgs(mapOf("direction" to "right")))
        assertNotNull(scrollTool.validateArgs(mapOf("direction" to "diagonal")))

        val readAloudTool = registry.getTool("read_screen_aloud")!!
        assertNull(readAloudTool.validateArgs(emptyMap()))

        val emailTool = registry.getTool("manage_email")!!
        assertNotNull(emailTool.validateArgs(emptyMap()))
        assertNull(emailTool.validateArgs(mapOf("action" to "read_email")))
        assertNull(emailTool.validateArgs(mapOf("action" to "delete_email")))
        assertNotNull(emailTool.validateArgs(mapOf("action" to "delete_all")))
    }

    @Test
    fun `offline assistant handles arithmetic time and honest cloud notice`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val offlineEngine = OfflineAssistantEngine(context, memoryMgr)
        val intentEngine = IntentEngine()

        val mathIntent = intentEngine.classify("what is 12 + 30")
        val (_, mathDecision) = offlineEngine.respond("what is 12 + 30", mathIntent, null)
        assertTrue(mathDecision.directReply.contains("42"))

        val timeIntent = intentEngine.classify("What time is it?")
        val (_, timeDecision) = offlineEngine.respond("What time is it?", timeIntent, null)
        assertTrue(timeDecision.directReply.contains("currently"))

        val cloudIntent = intentEngine.classify("Explain quantum entanglement")
        val (provider, cloudDecision) = offlineEngine.respond("Explain quantum entanglement", cloudIntent, null)
        assertTrue(provider.contains("Offline"))
        assertTrue(cloudDecision.directReply.contains("Offline Mode"))
    }

    @Test
    fun `task planner filters unknown tools and caps sequence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val registry = ToolRegistry(context, memoryMgr, settingsRepo)
        val planner = TaskPlanner(registry)

        val decision = ReasoningDecision(
            thoughtSummary = "Multi-step plan",
            directReply = "Working on it",
            plannedTools = listOf(
                PlannedToolCall("unknown_shell_exec", emptyMap(), "invalid"),
                PlannedToolCall("get_device_information", emptyMap(), "step 1"),
                PlannedToolCall("open_app", mapOf("appName" to "Settings"), "step 2"),
                PlannedToolCall("get_notifications", emptyMap(), "step 3"),
                PlannedToolCall("scroll_screen", mapOf("direction" to "down"), "step 4"),
                PlannedToolCall("press_back", emptyMap(), "step 5"),
                PlannedToolCall("open_browser", mapOf("url" to "https://example.com"), "step 6")
            )
        )
        val plan = planner.buildPlan(decision)
        assertEquals(5, plan.size)
        assertTrue(plan.none { it.toolId == "unknown_shell_exec" })
    }

    @Test
    fun `browser controller rejects unsafe URI schemes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val browser = BrowserController(context)
        assertTrue(browser.openUrl("javascript:alert(1)").isFailure)
        assertTrue(browser.openUrl("file:///etc/passwd").isFailure)
        assertTrue(browser.openUrl("").isFailure)
    }

    @Test
    fun `offline assistant handles chained math percentages and unit conversions`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val offlineEngine = OfflineAssistantEngine(context, memoryMgr)
        val intentEngine = IntentEngine()

        val chainIntent = intentEngine.classify("calculate 10 + 5 * 2")
        val (_, chainDecision) = offlineEngine.respond("calculate 10 + 5 * 2", chainIntent, null)
        assertTrue(chainDecision.directReply.contains("20"))

        val pctIntent = intentEngine.classify("20% of 500")
        val (_, pctDecision) = offlineEngine.respond("20% of 500", pctIntent, null)
        assertTrue(pctDecision.directReply.contains("100"))

        val convIntent = intentEngine.classify("10 km to miles")
        val (_, convDecision) = offlineEngine.respond("10 km to miles", convIntent, null)
        assertTrue(convDecision.directReply.contains("6.21"))
    }

    @Test
    fun `type text tool supports submit param and memory records thought summary`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val registry = ToolRegistry(context, memoryMgr, settingsRepo)

        val typeTool = registry.getTool("type_text")!!
        assertTrue(typeTool.parameters.any { it.name == "submit" })
        val navTool = registry.getTool("navigate_system")!!
        assertTrue(navTool.description.contains("quick_settings"))

        memoryMgr.recordConversationTurn(
            role = "assistant",
            content = "Opened YouTube and searched.",
            languageTag = "en",
            providerLabel = "Online • gemini-3.8-flash",
            thoughtSummary = "Step 1: Open YouTube\nStep 2: Type search query"
        )
        val msg = memoryMgr.getRecentConversation(5).lastOrNull()
        assertNotNull(msg)
        assertTrue(msg!!.thoughtSummary.contains("Step 1"))
    }

    @Test
    fun `privacy enforces userDisplayName only while chat memory stays RAM-only and clears on close`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = UltraRuntime.settingsRepository(context)
        val db = com.ultra.assistant.memory.UltraDatabase.getInstance(context)
        val memoryMgr = UltraRuntime.memoryManager(context)
        memoryMgr.clearTemporaryContext()

        // Cloud sync must remain disabled even if requested
        settingsRepo.updateSettings {
            it.copy(cloudSyncEnabled = true, encryptCloudMemory = true, userDisplayName = "Nadia")
        }
        val persisted = settingsRepo.settingsFlow.firstOrNullCompat()
        assertEquals("Nadia", persisted?.userDisplayName)
        assertEquals(false, persisted?.cloudSyncEnabled)
        assertEquals(false, persisted?.encryptCloudMemory)

        // Chats & session notes live in RAM only, never in Room disk tables
        memoryMgr.recordConversationTurn("user", "Hello ULTRA", "en")
        memoryMgr.saveMemory("SESSION_NOTE", "Note", "Likes black coffee", source = "user")
        memoryMgr.recordAuditLog("open_app", "LOW", "OK", "Opened YouTube", true)

        assertTrue(memoryMgr.getRecentConversation(5).isNotEmpty())
        assertTrue(memoryMgr.getPromptContextMemories(5).isNotEmpty())
        assertTrue(db.conversationDao().getLatest(10).isEmpty())
        assertTrue(db.memoryDao().getActiveMemories(0L, 10).isEmpty())

        // Closing session wipes RAM conversation & notes while keeping userDisplayName
        UltraRuntime.clearEphemeralSession(context)
        assertTrue(memoryMgr.getRecentConversation(5).isEmpty())
        assertTrue(memoryMgr.getPromptContextMemories(5).isEmpty())
        assertEquals("Nadia", settingsRepo.userDisplayName())
    }

    @Test
    fun `agent manager handles stop command and keeps session context across mode switches`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        settingsRepo.updateSettings { it.copy(operatingMode = com.ultra.assistant.data.AiOperatingMode.OFFLINE) }
        val agent = UltraRuntime.agentManager(context)
        agent.clearTemporaryContext()

        val nameReport = agent.handleUserCommand("আমার নাম সাকিব")
        assertEquals("সাকিব", settingsRepo.userDisplayName())
        assertTrue(nameReport.finalReply.contains("সাকিব"))

        val stopReport = agent.handleUserCommand("থামো")
        assertTrue(stopReport.providerUsed.contains("Stop"))
        assertTrue(stopReport.finalReply.contains("থেমে"))

        // Context remains intact in RAM across AUTO/OFFLINE mode switch
        agent.memoryManager.recordConversationTurn("user", "Remember topic alpha", "en")
        settingsRepo.updateSettings { it.copy(operatingMode = com.ultra.assistant.data.AiOperatingMode.AUTO) }
        assertTrue(agent.contextManager.getRecentHistoryPairs(5).any { it.second.contains("alpha") })
    }

    @Test
    fun `task executor enforces confirmation for email delete and accessibility checks for screen tools`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val registry = ToolRegistry(context, memoryMgr, settingsRepo)
        val executor = TaskExecutor(registry, memoryMgr, settingsRepo)

        val deleteGate = executor.executeTool(
            toolId = "manage_email",
            args = mapOf("action" to "delete_email"),
            userConfirmed = false
        )
        assertFalse(deleteGate.success)
        assertTrue(deleteGate.requiresConfirmation)
        assertTrue(deleteGate.summary.contains("Confirmation required"))

        val bulkBlocked = executor.executeTool(
            toolId = "manage_email",
            args = mapOf("action" to "delete_all_emails"),
            userConfirmed = true
        )
        assertFalse(bulkBlocked.success)
        assertFalse(bulkBlocked.requiresConfirmation)

        val readResult = executor.executeTool("read_screen_aloud", emptyMap())
        assertFalse(readResult.success)
        assertEquals(PermissionRequirement.ACCESSIBILITY, readResult.missingPermission)

        val swipeResult = executor.executeTool("scroll_screen", mapOf("direction" to "left"))
        assertFalse(swipeResult.success)
        assertEquals(PermissionRequirement.ACCESSIBILITY, swipeResult.missingPermission)
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstOrNullCompat(): T? {
    var value: T? = null
    try {
        collect {
            value = it
            throw kotlinx.coroutines.CancellationException("done")
        }
    } catch (_: kotlinx.coroutines.CancellationException) {
    }
    return value
}
