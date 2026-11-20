# ULTRA AI — JARVIS-Style Android AI Agent

ULTRA AI transforms `ULTRA-AI-Assistant` into an integrated, tool-using Android AI agent built in Kotlin and Jetpack Compose with a Cloudflare Workers gateway, Firebase AI Logic (`Firebase.ai`), Firebase Firestore cloud sync (gated by Google Sign-In via Jetpack Credential Manager), and AES-256-GCM encrypted local Room memory.

## Architecture Overview

- **AI Brain (`com.ultra.assistant.brain`)**:
  - `AIEngine` & `ModelRouter`: Multi-provider routing across Firebase AI Logic (`gemini-3.8-flash`, `gemini-3.5-flash-lite`, `gemini-4-argon`) and the Cloudflare Worker gateway (`POST /api/voice`).
  - `IntentEngine`, `ReasoningEngine`, `ContextManager`, `TaskPlanner`, `ResultVerifier`, and `AgentManager`.
- **Android Control Engine (`com.ultra.assistant.control`)**:
  - `UltraAccessibilityService` & `ScreenController`: Reads accessible UI trees, clicks controls by text/viewId, enters text into non-password fields, scrolls, and performs global back navigation.
  - `AppController`: Enumerates launchable apps, checks availability, launches apps, and opens Android App Info.
  - `UltraNotificationListenerService` & `NotificationRepository`: Captures permitted notifications with OTP/credential redaction.
  - `SmsController`: Prepares contextual SMS drafts via `ACTION_SENDTO`; sends directly only when configured as the device's default SMS handler and explicitly confirmed.
  - `BrowserController`: Validates `http://` / `https://` URLs and launches Google or YouTube Music search.
  - `DeviceManagementController` + `UltraDeviceAdminReceiver`: Reads device diagnostics and executes `lockNow()` only when user-activated in Android Device Admin settings.
- **Typed Tool Execution (`com.ultra.assistant.tools`)**:
  - 13 registered tools (`open_app`, `get_accessible_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_back`, `get_notifications`, `prepare_sms`, `open_browser`, `search_web`, `get_device_information`, `create_reminder`, `manage_approved_automation`) with argument validation, risk levels (`LOW`/`MEDIUM`/`HIGH`), confirmation dialogs, and persistent audit logs.
- **Voice & Overlay (`com.ultra.assistant`)**:
  - `VoiceAssistantService` (foreground microphone service), `SpeechRecognizer`, `TextToSpeech`, `WakeWordEngine`, `LanguageManager` (English, বাংলা, हिन्दी), `FloatingDot`, and WorkManager `TaskReminderWorker`.
- **Encrypted Memory & Privacy (`com.ultra.assistant.memory`)**:
  - Room database with `MemoryCrypto` (Android Keystore AES-256-GCM), `PrivacyGuard` (blocks OTPs/passwords/tokens/private SMS & notifications from auto-memory), TTL expiration, and user deletion.

## Permissions & Revocation Guide

- **Microphone & Notifications**: Requested at runtime for voice recognition and WorkManager reminders. Revoke anytime in Android App Settings.
- **Accessibility Service**: Opt-in in `Settings > Accessibility > ULTRA AI`. Required for screen inspection, clicking, typing, scrolling, and Back actions. Password fields are never written to or harvested.
- **Notification Access**: Opt-in in `Settings > Notification access`. Sensitive OTP/pin strings are automatically redacted.
- **Display Over Other Apps**: Opt-in for the optional `FloatingDot` quick-launch orb.
- **Device Administrator**: Opt-in for voice/agent `lock_screen`. Requires deactivation in `Settings > Security > Device admin apps` before uninstalling.

## Cloudflare Workers + D1 + KV Setup (Free Plan Compatible)

```bash
# 1. Create D1 database and update database_id in wrangler.toml
npx wrangler d1 create ultra_ai_db
npx wrangler d1 migrations apply ultra_ai_db --remote

# 2. Create KV namespace for rate limiting and update id in wrangler.toml
npx wrangler kv namespace create ULTRA_KV

# 3. Configure encrypted provider secrets
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put OPENAI_API_KEY
# Optional bearer auth secret:
npx wrangler secret put ULTRA_AUTH_TOKEN

# 4. Deploy Worker
npx wrangler deploy
```

## Known Platform Constraints

- **Wake word**: Transcript-based phrase matcher inside `SpeechRecognizer` sessions (not an always-on DSP acoustic hotword model). Continuous listening depends on Android version and OEM battery optimization policies.
- **SMS**: `prepare_sms` opens a pre-filled SMS composer draft by default; direct sending requires the user to set ULTRA AI as the default SMS app and confirm the `HIGH`-risk action.
- **Device Owner vs Device Admin**: ULTRA AI uses standard user-approved `DeviceAdminReceiver` (`force-lock`), not enterprise Device Owner provisioning.

## Building & Testing

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
