package com.ultra.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.ultra.assistant.data.UltraSettings
import com.ultra.assistant.firebase.authStateFlow
import com.ultra.assistant.firebase.configureAppCheck
import com.ultra.assistant.firebase.isFirebaseInitialized
import com.ultra.assistant.firebase.signOut
import com.ultra.assistant.permissions.PermissionManager
import com.ultra.assistant.tools.PermissionRequirement
import com.ultra.assistant.ui.UltraViewModel
import com.ultra.assistant.ui.apps.AppManagerScreen
import com.ultra.assistant.ui.auth.AuthScreen
import com.ultra.assistant.ui.automation.AutomationScreen
import com.ultra.assistant.ui.components.AmbientEdgeGlow
import com.ultra.assistant.ui.components.ChatBackgroundLayer
import com.ultra.assistant.ui.components.SensitiveConfirmationDialog
import com.ultra.assistant.ui.home.HomeScreen
import com.ultra.assistant.ui.settings.SettingsScreen
import com.ultra.assistant.ui.theme.UltraTheme
import com.ultra.assistant.ui.tools.ToolsAndControlScreen
import com.ultra.assistant.ui.voice.VoiceScreen

object UltraRoutes {
    const val HOME = "home"
    const val VOICE = "voice"
    const val CONTROL = "control"
    const val AUTOMATION = "automation"
    const val APPS = "apps"
    const val SETTINGS = "settings"
}

private data class NavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val primaryTabs = listOf(
    NavTab(UltraRoutes.HOME, "Chat", Icons.Default.Chat),
    NavTab(UltraRoutes.VOICE, "Voice", Icons.Default.Mic),
    NavTab(UltraRoutes.CONTROL, "Control", Icons.Default.Build),
    NavTab(UltraRoutes.AUTOMATION, "Auto", Icons.Default.AutoMode),
    NavTab(UltraRoutes.APPS, "Apps", Icons.Default.Apps),
    NavTab(UltraRoutes.SETTINGS, "Settings", Icons.Default.Settings)
)

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mandatory: configure App Check with launch intent before any Compose UI or ViewModels
        configureAppCheck(this, intent)
        pendingRoute.value = intent?.getStringExtra("open_route")
        enableEdgeToEdge()
        setContent {
            val rootSettings by UltraRuntime.settingsRepository(applicationContext)
                .settingsFlow
                .collectAsStateWithLifecycle(initialValue = UltraSettings())

            UltraTheme(themeStyle = rootSettings.uiThemeStyle) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UltraAppRoot(
                        requestedRoute = pendingRoute.value,
                        onRouteConsumed = { pendingRoute.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("open_route")?.let {
            pendingRoute.value = it
        }
    }
}

@Composable
fun UltraAppRoot(
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val firebaseReady = remember(context) { isFirebaseInitialized(context) }
    var offlineModeActive by remember { mutableStateOf(false) }

    if (!firebaseReady) {
        if (offlineModeActive) {
            UltraAuthenticatedShell(
                currentUserId = "local_offline_user",
                userEmail = "Offline Local Mode",
                requestedRoute = requestedRoute,
                onRouteConsumed = onRouteConsumed,
                onOfflineExit = { offlineModeActive = false }
            )
        } else {
            AuthScreen(
                firebaseAvailable = false,
                onAuthSuccess = {},
                onContinueOffline = { offlineModeActive = true }
            )
        }
        return
    }

    val auth = remember { runCatching { Firebase.auth }.getOrNull() }
    if (auth == null) {
        AuthScreen(
            firebaseAvailable = false,
            onAuthSuccess = {},
            onContinueOffline = { offlineModeActive = true }
        )
        return
    }

    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser
    if (user == null) {
        AuthScreen(
            firebaseAvailable = true,
            onAuthSuccess = {}
        )
    } else {
        key(user.uid) {
            UltraAuthenticatedShell(
                currentUserId = user.uid,
                userEmail = user.email,
                requestedRoute = requestedRoute,
                onRouteConsumed = onRouteConsumed
            )
        }
    }
}

@Composable
fun UltraAuthenticatedShell(
    currentUserId: String,
    userEmail: String?,
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
    onOfflineExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: UltraViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val settingsRepo = UltraRuntime.settingsRepository(app)
                val cloudRepo = UltraRuntime.cloudSyncRepository(app)
                val memoryMgr = UltraRuntime.memoryManager(app)
                val agentMgr = UltraRuntime.agentManager(app)
                val netMon = UltraRuntime.networkMonitor(app)
                UltraViewModel(
                    appContext = app.applicationContext,
                    currentUserId = currentUserId,
                    settingsRepository = settingsRepo,
                    cloudSyncRepository = cloudRepo,
                    memoryManager = memoryMgr,
                    agentManager = agentMgr,
                    networkMonitor = netMon
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeSessionId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val automationRules by viewModel.automationRules.collectAsStateWithLifecycle()
    val latestReport by viewModel.latestAgentReport.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val streamingReply by viewModel.streamingReply.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val screenSnapshot by viewModel.screenSnapshot.collectAsStateWithLifecycle()
    val isBusy by viewModel.isAgentBusy.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val permissions by viewModel.permissionSnapshot.collectAsStateWithLifecycle()
    val pendingReminders by viewModel.pendingReminders.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var isServiceRunning by remember { mutableStateOf(VoiceAssistantService.isRunning) }
    var voiceState by remember { mutableStateOf(VoiceAssistantService.currentState) }
    var voiceLevel by remember { mutableFloatStateOf(0f) }
    var partialTranscript by remember { mutableStateOf("") }
    var lastRecognizedText by remember { mutableStateOf("") }
    var lastAgentReply by remember { mutableStateOf("") }
    var pendingDotLaunch by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isServiceRunning = VoiceAssistantService.isRunning
                voiceState = VoiceAssistantService.currentState
                viewModel.refreshSystemState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    VoiceAssistantService.BROADCAST_VOICE_STATE -> {
                        voiceState = intent.getStringExtra("state") ?: "IDLE"
                        isServiceRunning = VoiceAssistantService.isRunning
                    }
                    VoiceAssistantService.BROADCAST_VOICE_PARTIAL -> {
                        partialTranscript = intent.getStringExtra("text").orEmpty()
                    }
                    VoiceAssistantService.BROADCAST_VOICE_TEXT -> {
                        partialTranscript = ""
                        lastRecognizedText = intent.getStringExtra("text").orEmpty()
                    }
                    VoiceAssistantService.BROADCAST_VOICE_LEVEL -> {
                        voiceLevel = intent.getFloatExtra("level", 0f)
                    }
                    VoiceAssistantService.BROADCAST_AGENT_REPLY -> {
                        lastAgentReply = intent.getStringExtra("reply").orEmpty()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(VoiceAssistantService.BROADCAST_VOICE_STATE)
            addAction(VoiceAssistantService.BROADCAST_VOICE_PARTIAL)
            addAction(VoiceAssistantService.BROADCAST_VOICE_TEXT)
            addAction(VoiceAssistantService.BROADCAST_VOICE_LEVEL)
            addAction(VoiceAssistantService.BROADCAST_AGENT_REPLY)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val safeStartActivity: (Intent) -> Unit = { targetIntent ->
        runCatching { context.startActivity(targetIntent) }.onFailure {
            Toast.makeText(context, "This settings screen is unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    val dispatchVoiceIntent: (Intent) -> Boolean = { svcIntent ->
        runCatching {
            if (VoiceAssistantService.isRunning) {
                context.startService(svcIntent)
            } else {
                ContextCompat.startForegroundService(context, svcIntent)
            }
        }.isSuccess
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshSystemState()
        val micGranted = PermissionManager.hasMicrophone(context)
        if (micGranted || results.values.all { it }) {
            val action = if (pendingDotLaunch) VoiceAssistantService.ACTION_SHOW_DOT else VoiceAssistantService.ACTION_START
            pendingDotLaunch = false
            val started = dispatchVoiceIntent(Intent(context, VoiceAssistantService::class.java).setAction(action))
            isServiceRunning = started || VoiceAssistantService.isRunning
            if (action == VoiceAssistantService.ACTION_SHOW_DOT && started) {
                viewModel.updateAssistantBehavior(true, settings.continuousListening, settings.requireCallConfirmation)
                Toast.makeText(context, "Floating assistant active", Toast.LENGTH_SHORT).show()
            } else if (started) {
                Toast.makeText(context, "ULTRA AI voice active", Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingDotLaunch = false
            Toast.makeText(context, "Microphone permission is required for voice control", Toast.LENGTH_LONG).show()
        }
    }

    val contactsPhoneLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshSystemState()
    }

    val startVoiceAssistant: () -> Unit = {
        val missing = PermissionManager.missing(context)
        if (missing.isNotEmpty()) {
            pendingDotLaunch = false
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            val started = dispatchVoiceIntent(
                Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_START)
            )
            isServiceRunning = started
            if (!started) {
                Toast.makeText(context, "Unable to start foreground voice service.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val stopVoiceAssistant: () -> Unit = {
        runCatching {
            context.startService(Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_STOP))
        }.onFailure {
            runCatching { context.stopService(Intent(context, VoiceAssistantService::class.java)) }
        }
        isServiceRunning = false
        voiceState = "IDLE"
    }

    val interruptVoiceAssistant: () -> Unit = {
        if (VoiceAssistantService.isRunning) {
            runCatching {
                context.startService(Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_INTERRUPT))
            }
        } else {
            startVoiceAssistant()
        }
    }

    val stopSpeakingOnly: () -> Unit = {
        if (VoiceAssistantService.isRunning) {
            runCatching {
                context.startService(Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_STOP_SPEAKING))
            }
        }
    }

    val speakTextOutLoud: (String) -> Unit = { text ->
        if (PermissionManager.hasMicrophone(context)) {
            dispatchVoiceIntent(
                Intent(context, VoiceAssistantService::class.java)
                    .setAction(VoiceAssistantService.ACTION_SPEAK_TEXT)
                    .putExtra(VoiceAssistantService.EXTRA_SPEAK_TEXT, text)
            )
        } else {
            val missing = PermissionManager.missing(context)
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val testVoicePreview: () -> Unit = {
        if (PermissionManager.hasMicrophone(context)) {
            dispatchVoiceIntent(
                Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_TEST_VOICE)
            )
        } else {
            val missing = PermissionManager.missing(context)
            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val enableFloatingDot: () -> Unit = {
        if (!PermissionManager.hasOverlay(context)) {
            safeStartActivity(PermissionManager.overlaySettingsIntent(context))
        } else if (!PermissionManager.hasMicrophone(context)) {
            pendingDotLaunch = true
            permissionLauncher.launch(PermissionManager.missing(context).toTypedArray())
        } else {
            val ok = dispatchVoiceIntent(
                Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_SHOW_DOT)
            )
            if (ok) {
                viewModel.updateAssistantBehavior(true, settings.continuousListening, settings.requireCallConfirmation)
                Toast.makeText(context, "Floating assistant enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Unable to enable floating overlay.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val disableFloatingDot: () -> Unit = {
        if (VoiceAssistantService.isRunning) {
            runCatching {
                context.startService(Intent(context, VoiceAssistantService::class.java).setAction(VoiceAssistantService.ACTION_HIDE_DOT))
            }
        }
        viewModel.updateAssistantBehavior(false, settings.continuousListening, settings.requireCallConfirmation)
    }

    val fixMissingPermission: (PermissionRequirement) -> Unit = { req ->
        when (req) {
            PermissionRequirement.ACCESSIBILITY -> safeStartActivity(PermissionManager.accessibilitySettingsIntent())
            PermissionRequirement.NOTIFICATION_LISTENER -> safeStartActivity(PermissionManager.notificationListenerSettingsIntent())
            PermissionRequirement.OVERLAY -> safeStartActivity(PermissionManager.overlaySettingsIntent(context))
            PermissionRequirement.DEVICE_ADMIN -> safeStartActivity(PermissionManager.deviceAdminActivateIntent(context))
            PermissionRequirement.MICROPHONE, PermissionRequirement.POST_NOTIFICATIONS -> {
                val missing = PermissionManager.missing(context)
                if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                else safeStartActivity(PermissionManager.appDetailsIntent(context))
            }
            PermissionRequirement.READ_CONTACTS, PermissionRequirement.CALL_PHONE -> {
                val missingCp = PermissionManager.missingContactsAndPhone(context)
                if (missingCp.isNotEmpty()) contactsPhoneLauncher.launch(missingCp.toTypedArray())
                else safeStartActivity(PermissionManager.appDetailsIntent(context))
            }
            PermissionRequirement.DEFAULT_SMS_APP -> safeStartActivity(PermissionManager.appDetailsIntent(context))
        }
    }

    pendingConfirmation?.let { pending ->
        SensitiveConfirmationDialog(
            confirmation = pending,
            onApprove = { viewModel.resolveConfirmation(true) },
            onDeny = { viewModel.resolveConfirmation(false) }
        )
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: UltraRoutes.HOME

    LaunchedEffect(requestedRoute) {
        if (!requestedRoute.isNullOrBlank()) {
            navController.navigate(requestedRoute) {
                popUpTo(UltraRoutes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onRouteConsumed()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatBackgroundLayer(backgroundStyle = settings.backgroundStyle)

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useNavRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                            tonalElevation = NavigationBarDefaults.Elevation,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                            primaryTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentRoute == tab.route,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(UltraRoutes.HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label, maxLines = 1) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        ) {
                            primaryTabs.forEach { tab ->
                                NavigationRailItem(
                                    selected = currentRoute == tab.route,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(UltraRoutes.HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = UltraRoutes.HOME
                        ) {
                            composable(UltraRoutes.HOME) {
                                HomeScreen(
                                    isServiceRunning = isServiceRunning,
                                    voiceState = voiceState,
                                    partialTranscript = partialTranscript,
                                    isOnline = isOnline,
                                    settings = settings,
                                    permissions = permissions,
                                    isBusy = isBusy,
                                    isStreaming = isStreaming,
                                    streamingReply = streamingReply,
                                    latestReport = latestReport,
                                    conversations = conversations,
                                    sessions = sessions,
                                    activeSessionId = activeSessionId,
                                    onStartAssistant = startVoiceAssistant,
                                    onStopAssistant = stopVoiceAssistant,
                                    onStopSpeaking = stopSpeakingOnly,
                                    onInterruptVoice = interruptVoiceAssistant,
                                    onEnableFloatingDot = {
                                        if (settings.floatingAssistantEnabled) disableFloatingDot() else enableFloatingDot()
                                    },
                                    onOpenVoiceScreen = {
                                        navController.navigate(UltraRoutes.VOICE) {
                                            popUpTo(UltraRoutes.HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    onSubmitCommand = { cmd -> viewModel.submitCommand(cmd) },
                                    onSpeakText = speakTextOutLoud,
                                    onStartNewChat = viewModel::startNewConversation,
                                    onSelectSession = viewModel::selectConversationSession,
                                    onDeleteSession = viewModel::deleteConversationSession,
                                    onFixPermission = fixMissingPermission
                                )
                            }
                            composable(UltraRoutes.VOICE) {
                                VoiceScreen(
                                    isServiceRunning = isServiceRunning,
                                    voiceState = voiceState,
                                    voiceLevel = voiceLevel,
                                    partialTranscript = partialTranscript,
                                    lastRecognizedText = lastRecognizedText,
                                    lastAgentReply = lastAgentReply,
                                    streamingReply = streamingReply,
                                    isOnline = isOnline,
                                    settings = settings,
                                    onStartVoice = startVoiceAssistant,
                                    onStopVoice = stopVoiceAssistant,
                                    onInterruptVoice = interruptVoiceAssistant,
                                    onStopSpeaking = stopSpeakingOnly,
                                    onEnableFloatingDot = enableFloatingDot,
                                    onTestVoice = testVoicePreview,
                                    onSelectVoiceGender = { gender ->
                                        viewModel.updateVoiceGender(gender)
                                        if (VoiceAssistantService.isRunning) {
                                            runCatching {
                                                context.startService(
                                                    Intent(context, VoiceAssistantService::class.java)
                                                        .setAction(VoiceAssistantService.ACTION_REFRESH_VOICE)
                                                )
                                            }
                                        }
                                    },
                                    onSelectLanguage = viewModel::updateLanguage,
                                    onUpdateWakePhrase = viewModel::updateWakePhrase,
                                    onUpdateVoiceTuning = viewModel::updateVoiceTuning,
                                    onUpdateVoiceVolume = viewModel::updateVoiceVolume,
                                    onToggleContinuousListening = { cont ->
                                        viewModel.updateAssistantBehavior(
                                            settings.floatingAssistantEnabled,
                                            cont,
                                            settings.requireCallConfirmation
                                        )
                                    }
                                )
                            }
                            composable(UltraRoutes.CONTROL) {
                                ToolsAndControlScreen(
                                    tools = viewModel.registeredTools,
                                    permissions = permissions,
                                    screenSnapshot = screenSnapshot,
                                    notifications = notifications,
                                    smsCaps = viewModel.smsCapabilities(),
                                    onRunToolTest = viewModel::runToolDirectly,
                                    onInspectScreen = viewModel::inspectCurrentScreen,
                                    onOpenAccessibilitySettings = { safeStartActivity(PermissionManager.accessibilitySettingsIntent()) },
                                    onOpenNotificationSettings = { safeStartActivity(PermissionManager.notificationListenerSettingsIntent()) },
                                    onClearNotifications = viewModel::clearNotifications,
                                    onPrepareSmsDraft = viewModel::prepareSmsDraft
                                )
                            }
                            composable(UltraRoutes.AUTOMATION) {
                                AutomationScreen(
                                    rules = automationRules,
                                    pendingReminders = pendingReminders,
                                    auditLogs = auditLogs,
                                    onScheduleReminder = viewModel::scheduleReminder,
                                    onCancelReminder = viewModel::cancelReminder,
                                    onAddRule = viewModel::addAutomationRule,
                                    onToggleRule = viewModel::toggleAutomationRule,
                                    onDeleteRule = viewModel::deleteAutomationRule,
                                    onClearAuditLogs = viewModel::clearAuditLogs
                                )
                            }
                            composable(UltraRoutes.APPS) {
                                AppManagerScreen(
                                    apps = installedApps,
                                    onLaunchApp = viewModel::launchApp,
                                    onOpenAppInfo = viewModel::openAppInfo
                                )
                            }
                            composable(UltraRoutes.SETTINGS) {
                                SettingsScreen(
                                    userEmail = userEmail,
                                    isOnline = isOnline,
                                    settings = settings,
                                    permissions = permissions,
                                    deviceInfo = viewModel.deviceInfo(),
                                    deviceAdminExplanation = viewModel.deviceAdminExplanation(),
                                    memories = memories,
                                    sessions = sessions,
                                    onUpdateOperatingMode = viewModel::updateOperatingMode,
                                    onUpdateAiConfig = viewModel::updateAiConfig,
                                    onUpdateVoiceGender = viewModel::updateVoiceGender,
                                    onSelectLanguage = viewModel::updateLanguage,
                                    onUpdateWakePhrase = viewModel::updateWakePhrase,
                                    onUpdateVoiceTuning = viewModel::updateVoiceTuning,
                                    onUpdateVoiceVolume = viewModel::updateVoiceVolume,
                                    onTestVoice = testVoicePreview,
                                    onUpdateAppearance = viewModel::updateAppearanceConfig,
                                    onUpdateBehavior = { floating, continuous, callConfirm ->
                                        viewModel.updateAssistantBehavior(floating, continuous, callConfirm)
                                        if (floating) {
                                            enableFloatingDot()
                                        } else if (VoiceAssistantService.isRunning) {
                                            runCatching {
                                                context.startService(
                                                    Intent(context, VoiceAssistantService::class.java)
                                                        .setAction(VoiceAssistantService.ACTION_HIDE_DOT)
                                                )
                                            }
                                        }
                                    },
                                    onLaunchFloatingDot = enableFloatingDot,
                                    onUpdatePrivacyConfig = viewModel::updatePrivacyConfig,
                                    onSaveMemory = viewModel::saveMemoryManual,
                                    onDeleteMemory = viewModel::deleteMemory,
                                    onClearAllMemories = viewModel::clearAllMemories,
                                    onStartNewChat = viewModel::startNewConversation,
                                    onClearConversationHistory = viewModel::clearConversationHistoryOnly,
                                    onRequestContactsAndPhone = {
                                        val missingCp = PermissionManager.missingContactsAndPhone(context)
                                        if (missingCp.isNotEmpty()) {
                                            contactsPhoneLauncher.launch(missingCp.toTypedArray())
                                        } else {
                                            safeStartActivity(PermissionManager.appDetailsIntent(context))
                                        }
                                    },
                                    onOpenMicAppSettings = { safeStartActivity(PermissionManager.appDetailsIntent(context)) },
                                    onOpenOverlaySettings = { safeStartActivity(PermissionManager.overlaySettingsIntent(context)) },
                                    onOpenAccessibilitySettings = { safeStartActivity(PermissionManager.accessibilitySettingsIntent()) },
                                    onOpenNotificationSettings = { safeStartActivity(PermissionManager.notificationListenerSettingsIntent()) },
                                    onOpenBatterySettings = { safeStartActivity(PermissionManager.batteryOptimizationSettingsIntent()) },
                                    onActivateDeviceAdmin = { safeStartActivity(PermissionManager.deviceAdminActivateIntent(context)) },
                                    onOpenSecuritySettings = { safeStartActivity(PermissionManager.securitySettingsIntent()) },
                                    onSignOut = {
                                        if (VoiceAssistantService.isRunning) stopVoiceAssistant()
                                        if (onOfflineExit != null) {
                                            onOfflineExit()
                                        } else {
                                            signOut(credentialManager, scope)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Subtle animated edge glow around perimeter while active
        AmbientEdgeGlow(
            isActive = isServiceRunning || isBusy || isStreaming,
            voiceState = voiceState,
            enabled = settings.ambientEdgeGlowEnabled
        )
    }
}
