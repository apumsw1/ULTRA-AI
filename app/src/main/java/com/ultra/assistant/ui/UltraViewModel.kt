package com.ultra.assistant.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultra.assistant.automation.RuleManager
import com.ultra.assistant.automation.TaskScheduler
import com.ultra.assistant.brain.AgentExecutionReport
import com.ultra.assistant.brain.AgentManager
import com.ultra.assistant.brain.PendingToolConfirmation
import com.ultra.assistant.brain.UiState
import com.ultra.assistant.control.AccessibleScreenSnapshot
import com.ultra.assistant.control.AppController
import com.ultra.assistant.control.BrowserController
import com.ultra.assistant.control.CapturedNotification
import com.ultra.assistant.control.DeviceInfoReport
import com.ultra.assistant.control.DeviceManagementController
import com.ultra.assistant.control.InstalledAppInfo
import com.ultra.assistant.control.NotificationRepository
import com.ultra.assistant.control.ScreenController
import com.ultra.assistant.control.SmsCapabilityReport
import com.ultra.assistant.control.SmsController
import com.ultra.assistant.control.UltraAccessibilityService
import com.ultra.assistant.data.AiOperatingMode
import com.ultra.assistant.data.AiProviderMode
import com.ultra.assistant.data.AiSpeedProfile
import com.ultra.assistant.data.ChatBackgroundStyle
import com.ultra.assistant.data.LocalSettingsRepository
import com.ultra.assistant.data.NetworkMonitor
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.data.UltraThemeStyle
import com.ultra.assistant.data.VoiceGender
import com.ultra.assistant.firebase.CloudSyncRepository
import com.ultra.assistant.firebase.SyncedMemory
import com.ultra.assistant.memory.AuditLogEntity
import com.ultra.assistant.memory.AutomationRuleEntity
import com.ultra.assistant.memory.ConversationMessageEntity
import com.ultra.assistant.memory.ConversationSessionEntity
import com.ultra.assistant.memory.MemoryManager
import com.ultra.assistant.memory.MemoryRecordEntity
import com.ultra.assistant.permissions.PermissionAuditSnapshot
import com.ultra.assistant.permissions.PermissionManager
import com.ultra.assistant.tools.ToolExecutionResult
import com.ultra.assistant.tools.UltraTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UltraViewModel(
    private val appContext: Context,
    private val currentUserId: String,
    private val settingsRepository: LocalSettingsRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val memoryManager: MemoryManager,
    private val agentManager: AgentManager,
    private val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)
) : ViewModel() {

    private val appController = AppController(appContext)
    private val screenController = ScreenController(appContext)
    private val smsController = SmsController(appContext)
    private val browserController = BrowserController(appContext)
    private val deviceController = DeviceManagementController(appContext)
    private val taskScheduler = TaskScheduler(appContext)
    private val ruleManager = RuleManager(appContext)

    val settings: StateFlow<UltraSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UltraSettings())

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val isReconnecting: StateFlow<Boolean> = networkMonitor.isReconnecting

    val memories: StateFlow<List<MemoryRecordEntity>> = memoryManager.memoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val sessions: StateFlow<List<ConversationSessionEntity>> = memoryManager.sessionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val activeSessionId: StateFlow<String> = memoryManager.activeSessionIdFlow

    val conversations: StateFlow<List<ConversationMessageEntity>> = memoryManager.conversationFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val auditLogs: StateFlow<List<AuditLogEntity>> = memoryManager.auditLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val automationRules: StateFlow<List<AutomationRuleEntity>> = memoryManager.automationRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Cloud memory sync is permanently disabled per strict privacy rules.
    val cloudMemoriesState: StateFlow<UiState<List<SyncedMemory>>> =
        MutableStateFlow<UiState<List<SyncedMemory>>>(UiState.Success(emptyList())).asStateFlow()

    val latestAgentReport: StateFlow<AgentExecutionReport?> = agentManager.latestReportFlow
    val pendingConfirmation: StateFlow<PendingToolConfirmation?> = agentManager.pendingConfirmationFlow
    val streamingReply: StateFlow<String> = agentManager.streamingReplyFlow
    val isStreaming: StateFlow<Boolean> = agentManager.isStreamingFlow
    val liveAgentStatus: StateFlow<String> = agentManager.liveStatusFlow
    val onlineDiagnostic: StateFlow<String> = agentManager.modelRouter.onlineDiagnosticFlow
    val notifications: StateFlow<List<CapturedNotification>> = NotificationRepository.notificationsFlow
    val screenSnapshot: StateFlow<AccessibleScreenSnapshot?> = UltraAccessibilityService.latestSnapshotFlow

    private val _isAgentBusy = MutableStateFlow(false)
    val isAgentBusy: StateFlow<Boolean> = _isAgentBusy.asStateFlow()

    private val _workerHealthStatus = MutableStateFlow<String?>(null)
    val workerHealthStatus: StateFlow<String?> = _workerHealthStatus.asStateFlow()

    private var activeCommandJob: Job? = null

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _permissionSnapshot = MutableStateFlow(PermissionManager.snapshot(appContext))
    val permissionSnapshot: StateFlow<PermissionAuditSnapshot> = _permissionSnapshot.asStateFlow()

    private val _pendingReminders = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val pendingReminders: StateFlow<List<Pair<String, String>>> = _pendingReminders.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    val registeredTools: List<UltraTool> = agentManager.toolRegistry.allTools()

    init {
        refreshSystemState()
        viewModelScope.launch {
            var wasOnline = networkMonitor.isOnline.value
            networkMonitor.isOnline.collect { nowOnline ->
                if (!wasOnline && nowOnline) {
                    agentManager.modelRouter.clearTransientDiagnostic()
                }
                wasOnline = nowOnline
            }
        }
    }

    fun refreshSystemState() {
        _permissionSnapshot.value = PermissionManager.snapshot(appContext)
        _pendingReminders.value = taskScheduler.pendingReminders()
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { appController.listInstalledApps() }
            _installedApps.value = apps
            memoryManager.purgeExpiredMemories()
        }
    }

    fun submitCommand(input: String, onFinished: ((AgentExecutionReport) -> Unit)? = null) {
        val clean = input.trim()
        if (clean.isBlank() || _isAgentBusy.value) return
        activeCommandJob = viewModelScope.launch {
            _isAgentBusy.value = true
            try {
                val report = agentManager.handleUserCommand(clean)
                onFinished?.invoke(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage ?: "Agent execution failed."
            } finally {
                _isAgentBusy.value = false
                refreshSystemState()
            }
        }
    }

    fun stopAgentExecution() {
        agentManager.requestStop()
        activeCommandJob?.cancel()
        activeCommandJob = null
        _isAgentBusy.value = false
    }

    fun startNewConversation() {
        viewModelScope.launch {
            memoryManager.startNewSession()
            _statusBanner.value = "Started a new conversation."
        }
    }

    fun selectConversationSession(sessionId: String) {
        memoryManager.selectSession(sessionId)
    }

    fun deleteConversationSession(sessionId: String) {
        viewModelScope.launch {
            memoryManager.deleteSession(sessionId)
            _statusBanner.value = "Conversation removed."
        }
    }

    fun clearConversationHistoryOnly() {
        viewModelScope.launch {
            memoryManager.clearConversationHistory()
            _statusBanner.value = "Conversation history cleared."
        }
    }

    fun resolveConfirmation(approved: Boolean) {
        viewModelScope.launch {
            agentManager.resolvePendingConfirmation(approved)
            refreshSystemState()
        }
    }

    fun runToolDirectly(toolId: String, args: Map<String, String>) {
        viewModelScope.launch {
            val tool = agentManager.toolRegistry.getTool(toolId) ?: return@launch
            val result: ToolExecutionResult = tool.execute(args)
            memoryManager.recordAuditLog(toolId, tool.riskLevel.name, if (result.success) "SUCCESS" else "FAILED", result.summary, result.success)
            _statusBanner.value = result.summary
            refreshSystemState()
        }
    }

    fun inspectCurrentScreen() {
        viewModelScope.launch {
            val res = screenController.inspectScreen()
            _statusBanner.value = res.fold(
                onSuccess = { "Captured ${it.elements.size} UI nodes from ${it.packageName}." },
                onFailure = { it.message ?: "Accessibility unavailable." }
            )
        }
    }

    fun prepareSmsDraft(recipient: String, message: String) {
        val res = smsController.prepareDraftIntent(recipient, message)
        _statusBanner.value = res.message
        viewModelScope.launch {
            memoryManager.recordAuditLog("prepare_sms", "MEDIUM", if (res.success) "SUCCESS" else "FAILED", res.message, res.success)
        }
    }

    fun smsCapabilities(): SmsCapabilityReport = smsController.capabilities()

    fun deviceInfo(): DeviceInfoReport = deviceController.getDeviceInfo()

    fun deviceAdminExplanation(): String = deviceController.explainAdminVsOwner()

    fun launchApp(packageName: String) {
        appController.launchApp(packageName).onFailure {
            _statusBanner.value = it.message ?: "Unable to launch app."
        }
    }

    fun openAppInfo(packageName: String) {
        appController.openAppInfo(packageName)
    }

    fun openBrowserUrl(url: String) {
        browserController.openUrl(url).onFailure {
            _statusBanner.value = it.message ?: "Invalid URL."
        }
    }

    fun scheduleReminder(title: String, delayMinutes: Long) {
        runCatching {
            taskScheduler.scheduleReminder(title, delayMinutes)
            _statusBanner.value = "Scheduled reminder '$title' in $delayMinutes min."
            refreshSystemState()
        }.onFailure {
            _statusBanner.value = it.message ?: "Invalid reminder parameters."
        }
    }

    fun cancelReminder(workId: String) {
        taskScheduler.cancel(workId)
        refreshSystemState()
    }

    fun addAutomationRule(name: String, description: String, actionTool: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            ruleManager.add(name, description.ifBlank { "User-approved workflow" })
            memoryManager.saveAutomationRule(
                name = name,
                description = description.ifBlank { "User-approved workflow" },
                triggerType = "MANUAL_WORKFLOW",
                actionTool = actionTool.ifBlank { "open_app" },
                actionArgsJson = "{}"
            )
            _statusBanner.value = "Saved automation '$name'."
        }
    }

    fun toggleAutomationRule(rule: AutomationRuleEntity) {
        viewModelScope.launch { memoryManager.toggleAutomationRule(rule) }
    }

    fun deleteAutomationRule(rule: AutomationRuleEntity) {
        viewModelScope.launch {
            ruleManager.remove(rule.name)
            memoryManager.deleteAutomationRule(rule.id)
        }
    }

    fun updateUserDisplayName(name: String) {
        val clean = name.trim().take(60)
        viewModelScope.launch {
            settingsRepository.updateUserDisplayName(clean)
            _statusBanner.value = if (clean.isBlank()) {
                "Saved name cleared."
            } else {
                "Saved your name: $clean"
            }
        }
    }

    fun saveMemoryManual(title: String, content: String) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            val saved = memoryManager.saveMemory("SESSION_NOTE", title, content, source = "user")
            _statusBanner.value = if (saved) {
                "Added temporary session note (clears when app closes)."
            } else {
                "Blocked by Privacy Guard: sensitive credentials or OTPs cannot be stored."
            }
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { memoryManager.deleteMemory(id) }
    }

    fun clearAllMemories() {
        viewModelScope.launch {
            memoryManager.clearTemporaryContext()
            agentManager.contextManager.clearTemporaryContext()
            _statusBanner.value = "Temporary session chats & notes cleared."
        }
    }

    fun clearAuditLogs() {
        viewModelScope.launch { memoryManager.clearAuditLogs() }
    }

    fun clearNotifications() {
        NotificationRepository.clearAll()
    }

    fun updateLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(language = language) }
        }
    }

    fun updateWakePhrase(phrase: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(wakePhrase = phrase) }
        }
    }

    fun updateVoiceGender(gender: VoiceGender) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(voiceGender = gender) }
            _statusBanner.value = "Voice switched to ${gender.label}."
        }
    }

    fun updateVoiceTuning(speed: Float, pitch: Float) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(voiceSpeed = speed, voicePitch = pitch) }
        }
    }

    fun updateVoiceVolume(volume: Float) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(voiceVolume = volume) }
        }
    }

    fun updateOperatingMode(mode: AiOperatingMode) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(operatingMode = mode) }
            _statusBanner.value = "Switched to ${mode.label}."
        }
    }

    fun updateSpeedProfile(profile: AiSpeedProfile) {
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(aiSpeedProfile = profile, geminiModel = profile.modelId)
            }
            _statusBanner.value = "AI Brain speed set to ${profile.label}."
        }
    }

    fun updateLanguageAutoDetect(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(languageAutoDetect = enabled) }
        }
    }

    fun updateAutoSpeakChatReplies(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(autoSpeakChatReplies = enabled) }
        }
    }

    fun updateAppearanceConfig(
        backgroundStyle: ChatBackgroundStyle,
        themeStyle: UltraThemeStyle,
        ambientEdgeGlowEnabled: Boolean
    ) {
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(
                    backgroundStyle = backgroundStyle,
                    uiThemeStyle = themeStyle,
                    ambientEdgeGlowEnabled = ambientEdgeGlowEnabled
                )
            }
        }
    }

    fun updateAssistantBehavior(
        floatingEnabled: Boolean,
        continuousListening: Boolean,
        requireCallConfirmation: Boolean
    ) {
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(
                    floatingAssistantEnabled = floatingEnabled,
                    continuousListening = continuousListening,
                    requireCallConfirmation = requireCallConfirmation
                )
            }
        }
    }

    fun updateAiConfig(
        mode: AiProviderMode,
        geminiModel: String,
        workerUrl: String,
        workerToken: String = settings.value.workerAuthToken
    ) {
        val matchingProfile = AiSpeedProfile.entries.firstOrNull { it.modelId == geminiModel }
            ?: settings.value.aiSpeedProfile
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(
                    aiProviderMode = mode,
                    aiSpeedProfile = matchingProfile,
                    geminiModel = geminiModel,
                    workerEndpoint = workerUrl,
                    workerAuthToken = workerToken
                )
            }
            _statusBanner.value = "AI Brain configuration updated."
        }
    }

    fun testWorkerConnection(workerUrl: String, workerToken: String) {
        viewModelScope.launch {
            _workerHealthStatus.value = "Testing Cloudflare Worker..."
            val res = agentManager.modelRouter.testWorkerHealth(workerUrl, workerToken)
            val msg = res.getOrElse { "Failed: ${it.message ?: "unreachable"}" }
            _workerHealthStatus.value = msg
            _statusBanner.value = msg
        }
    }

    fun updatePrivacyConfig(
        cloudSync: Boolean,
        encryptCloud: Boolean,
        ttlDays: Int,
        notifContext: Boolean,
        smsConfirm: Boolean,
        callConfirm: Boolean = settings.value.requireCallConfirmation
    ) {
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(
                    cloudSyncEnabled = cloudSync,
                    encryptCloudMemory = encryptCloud,
                    memoryTtlDays = ttlDays,
                    allowNotificationContext = notifContext,
                    requireSmsConfirmation = smsConfirm,
                    requireCallConfirmation = callConfirm
                )
            }
            _statusBanner.value = "Privacy & Memory settings saved."
        }
    }

    fun openSystemSettings(action: String) {
        appController.openSettingsAction(action)
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }
}
