# ULTRA AI — JARVIS-ধাঁচের অ্যান্ড্রয়েড এআই এজেন্ট

ULTRA AI বিদ্যমান `ULTRA-AI-Assistant` প্রজেক্টটিকে Kotlin ও Jetpack Compose-ভিত্তিক একটি সম্পূর্ণ টুল-ব্যবহারকারী অ্যান্ড্রয়েড এআই এজেন্টে রূপান্তর করে। এতে রয়েছে Cloudflare Workers গেটওয়ে, Firebase AI Logic (`Firebase.ai`), Google Sign-In ভিত্তিক Firebase Firestore ক্লাউড সিঙ্ক, এবং AES-256-GCM এনক্রিপ্টেড লোকাল Room মেমরি।

## মূল আর্কিটেকচার

- **AI Brain (`com.ultra.assistant.brain`)**:
  - `AIEngine` ও `ModelRouter`: Firebase AI Logic (`gemini-3.8-flash`, `gemini-3.5-flash-lite`, `gemini-4-argon`) এবং Cloudflare Worker (`POST /api/voice`) এর মধ্যে মাল্টি-প্রোভাইডার রাউটিং।
  - `IntentEngine`, `ReasoningEngine`, `ContextManager`, `TaskPlanner`, `ResultVerifier`, এবং `AgentManager`।
- **Android Control Engine (`com.ultra.assistant.control`)**:
  - `UltraAccessibilityService` ও `ScreenController`: অ্যাক্সেসিবল স্ক্রিন রিডিং, বাটন ক্লিক, নন-পাসওয়ার্ড ফিল্ডে টাইপিং, স্ক্রলিং এবং গ্লোবাল ব্যাক ন্যাভিগেশন।
  - `AppController`: ইনস্টল করা অ্যাপ খুঁজে বের করা, চালু করা এবং App Info খোলা।
  - `UltraNotificationListenerService` ও `NotificationRepository`: অনুমোদিত নোটিফিকেশন পড়া এবং OTP/সংবেদনশীল তথ্য মাস্ক করা।
  - `SmsController`: মেসেজিং অ্যাপে SMS ড্রাফট তৈরি করা; কেবল ডিফল্ট SMS অ্যাপ হিসেবে সেট ও কনফার্ম করা হলে সরাসরি পাঠানো।
  - `BrowserController`: বৈধ `http://` / `https://` লিংক এবং Google / YouTube Music সার্চ খোলা।
  - `DeviceManagementController` + `UltraDeviceAdminReceiver`: ডিভাইস তথ্য সংগ্রহ এবং ব্যবহারকারীর অনুমতি সাপেক্ষে স্ক্রিন লক।
- **ভয়েস এবং মেমরি**:
  - বাংলা, English ও हिन्दी সাপোর্ট (`LanguageManager`), কনফিগারেবল ওয়েক ফ্রেজ (`WakeWordEngine`), ফোরগ্রাউন্ড মাইক্রোফোন সার্ভিস, ফ্লোটিং ডট এবং WorkManager রিমাইন্ডার।
  - `MemoryCrypto` (Android Keystore AES-256-GCM) ও `PrivacyGuard` দিয়ে সুরক্ষিত Room মেমরি।

## অনুমতি ও নিয়ন্ত্রণ

- **Microphone ও Notifications**: ভয়েস কমান্ড এবং রিমাইন্ডারের জন্য রানটাইম অনুমতি।
- **Accessibility Service**: স্ক্রিন দেখা ও UI কন্ট্রোল ব্যবহারের জন্য Android Accessibility সেটিংস থেকে ম্যানুয়ালি চালু করতে হয়। পাসওয়ার্ড ফিল্ডে অটো-টাইপিং বা সংগ্রহ নিষিদ্ধ।
- **Notification Access**: নোটিফিকেশন পড়ার জন্য সিস্টেম সেটিংস থেকে অনুমতি দিতে হয়।
- **Display Over Other Apps**: ভাসমান `FloatingDot` ব্যবহারের জন্য প্রয়োজন।
- **Device Administrator**: `lock phone` / `ফোন লক` কমান্ডের জন্য সক্রিয় করতে হয় (অ্যাপ আনইনস্টল করার আগে Device Admin বন্ধ করতে হবে)।

## Cloudflare Workers + D1 + KV সেটআপ

```bash
npx wrangler d1 create ultra_ai_db
npx wrangler d1 migrations apply ultra_ai_db --remote
npx wrangler kv namespace create ULTRA_KV
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put OPENAI_API_KEY
npx wrangler deploy
```

## সীমাবদ্ধতা

- Wake word সিস্টেমটি `SpeechRecognizer`-এর ট্রান্সক্রিপ্ট ম্যাচিং ব্যবহার করে (লো-পাওয়ার হার্ডওয়্যার হটওয়ার্ড মডেল নয়)।
- ডিফল্টভাবে `prepare_sms` নিরাপদ SMS ড্রাফট খোলে।
- GitHub Actions workflow-তে `gradle --no-daemon testDebugUnitTest` এবং `gradle --no-daemon assembleDebug` চালিয়ে APK তৈরি করা হয়।
