package com.ultra.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ultra.assistant.brain.PlannedToolCall
import com.ultra.assistant.brain.ReasoningDecision
import com.ultra.assistant.brain.TaskPlanner
import com.ultra.assistant.control.BrowserController
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.firebase.CloudSyncRepository
import com.ultra.assistant.memory.MemoryManager
import com.ultra.assistant.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UltraRobolectricTest {

    @Test
    fun `app name resource matches ULTRA AI identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ULTRA AI", context.getString(R.string.app_name))
    }

    @Test
    fun `tool registry validates all 13 tools and rejects bad params`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsRepo = LocalSettingsRepository(context)
        val memoryMgr = MemoryManager(context, settingsRepo, CloudSyncRepository(context))
        val registry = ToolRegistry(context, memoryMgr, settingsRepo)

        assertEquals(13, registry.allTools().size)

        val openApp = registry.getTool("open_app")!!
        assertNotNull(openApp.validateArgs(emptyMap()))
        assertNull(openApp.validateArgs(mapOf("appName" to "Settings")))

        val clickTool = registry.getTool("click_ui_element")!!
        assertNotNull(clickTool.validateArgs(emptyMap()))
        assertNull(clickTool.validateArgs(mapOf("targetText" to "Allow")))

        val reminderTool = registry.getTool("create_reminder")!!
        assertNotNull(reminderTool.validateArgs(mapOf("title" to "Call", "delayMinutes" to "abc")))
        assertNull(reminderTool.validateArgs(mapOf("title" to "Call", "delayMinutes" to "15")))
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
}
