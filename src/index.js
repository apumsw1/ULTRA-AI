/**
 * ULTRA AI — Cloudflare Worker Gateway & Agent Control Plane
 * Free Plan compatible: supports Cloudflare Workers AI (`env.AI`), Gemini REST, OpenAI,
 * plus optional D1 SQLite & KV rate limiting.
 */

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PATCH, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Ultra-Client",
  "Content-Type": "application/json; charset=utf-8"
};

/**
 * Canonical 19-tool schema registry mirrored with Android ToolRegistry.kt.
 */
const TOOL_SCHEMAS = {
  open_app: {
    riskLevel: "LOW",
    required: ["appName"],
    optional: [],
    description: "Launch an installed Android application by name or package ID."
  },
  search_youtube: {
    riskLevel: "LOW",
    required: ["query"],
    optional: [],
    description: "Search YouTube videos or channels."
  },
  control_media_volume: {
    riskLevel: "LOW",
    required: [],
    anyOf: ["volumeAction", "mediaAction"],
    optional: ["volumeAction", "levelPercent", "mediaAction"],
    description: "Control media volume (up/down/mute/unmute/set) or playback (play/pause/play_pause/next/previous/stop)."
  },
  toggle_flashlight: {
    riskLevel: "LOW",
    required: [],
    optional: ["state"],
    enumValues: { state: ["on", "off", "toggle"] },
    description: "Turn device flashlight on, off, or toggle."
  },
  open_device_settings: {
    riskLevel: "LOW",
    required: ["target"],
    optional: [],
    description: "Open Android settings/panels: wifi, bluetooth, network, sound, brightness, notifications, battery, main."
  },
  make_phone_call: {
    riskLevel: "HIGH",
    required: ["target"],
    optional: ["directCall"],
    description: "Look up a contact or number and dial/call after confirmation."
  },
  navigate_system: {
    riskLevel: "LOW",
    required: ["action"],
    optional: [],
    enumValues: { action: ["home", "recents", "notifications", "back", "quick_settings", "lock_screen"] },
    description: "Global Android system navigation via Accessibility."
  },
  get_accessible_screen: {
    riskLevel: "MEDIUM",
    required: [],
    optional: [],
    description: "Inspect active Android screen UI controls via AccessibilityService."
  },
  click_ui_element: {
    riskLevel: "MEDIUM",
    required: [],
    anyOf: ["targetText", "viewId"],
    optional: ["targetText", "viewId"],
    description: "Click a visible UI control by text label or view ID."
  },
  type_text: {
    riskLevel: "MEDIUM",
    required: ["text"],
    optional: ["targetField", "submit"],
    description: "Enter text into an editable non-password field and optionally submit."
  },
  scroll_screen: {
    riskLevel: "LOW",
    required: [],
    optional: ["direction"],
    enumValues: { direction: ["up", "down"] },
    description: "Scroll the active accessible window up or down."
  },
  press_back: {
    riskLevel: "LOW",
    required: [],
    optional: [],
    description: "Perform global Android Back navigation."
  },
  get_notifications: {
    riskLevel: "MEDIUM",
    required: [],
    optional: ["limit"],
    description: "Read recent privacy-filtered notifications."
  },
  prepare_sms: {
    riskLevel: "HIGH",
    required: ["message"],
    optional: ["recipient", "directSend"],
    description: "Prepare a contextual SMS draft or request confirmation if authorized."
  },
  open_browser: {
    riskLevel: "LOW",
    required: ["url"],
    optional: [],
    description: "Open a validated http/https URL in browser."
  },
  search_web: {
    riskLevel: "LOW",
    required: ["query"],
    optional: ["platform"],
    enumValues: { platform: ["google", "youtube", "youtube_music", "music"] },
    description: "Search Google, YouTube, or YouTube Music."
  },
  get_device_information: {
    riskLevel: "LOW",
    required: [],
    optional: ["action"],
    enumValues: { action: ["info", "lock_screen"] },
    description: "Get device telemetry or request authorized screen lock."
  },
  create_reminder: {
    riskLevel: "LOW",
    required: ["title", "delayMinutes"],
    optional: [],
    description: "Schedule a delayed WorkManager notification reminder."
  },
  manage_approved_automation: {
    riskLevel: "MEDIUM",
    required: [],
    optional: ["operation", "name", "description", "actionTool"],
    enumValues: { operation: ["add", "list"] },
    description: "Create or list user-approved automation workflows."
  }
};

function reply(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: CORS_HEADERS
  });
}

function logStructured(level, event, details = {}) {
  const entry = JSON.stringify({
    ts: new Date().toISOString(),
    level,
    event,
    ...details
  });
  if (level === "error") console.error(entry);
  else if (level === "warn") console.warn(entry);
  else console.log(entry);
}

function validateToolCall(toolId, argsObj = {}) {
  const schema = TOOL_SCHEMAS[toolId];
  if (!schema) {
    return { valid: false, errors: [`Unknown toolId '${toolId}'`], riskLevel: "HIGH" };
  }
  const errors = [];
  const args = argsObj && typeof argsObj === "object" ? argsObj : {};

  for (const key of schema.required || []) {
    const val = args[key];
    if (val === undefined || val === null || String(val).trim() === "") {
      errors.push(`Missing required parameter '${key}'`);
    }
  }
  if (Array.isArray(schema.anyOf) && schema.anyOf.length > 0) {
    const hasOne = schema.anyOf.some((k) => args[k] !== undefined && String(args[k]).trim() !== "");
    if (!hasOne) {
      errors.push(`Provide at least one of: ${schema.anyOf.join(", ")}`);
    }
  }
  if (schema.enumValues) {
    for (const [k, allowed] of Object.entries(schema.enumValues)) {
      if (args[k] !== undefined && String(args[k]).trim() !== "") {
        if (!allowed.includes(String(args[k]).toLowerCase())) {
          errors.push(`Invalid value for '${k}': allowed=${allowed.join("|")}`);
        }
      }
    }
  }
  if (toolId === "open_browser" && args.url) {
    const u = String(args.url).trim().toLowerCase();
    if (!u.startsWith("https://") && !u.startsWith("http://")) {
      errors.push("URL must use http:// or https://");
    }
  }
  return { valid: errors.length === 0, errors, riskLevel: schema.riskLevel };
}

function normalizeAndFilterToolCalls(rawToolCalls) {
  if (!Array.isArray(rawToolCalls)) return [];
  const validCalls = [];
  for (const call of rawToolCalls.slice(0, 6)) {
    if (!call || typeof call.toolId !== "string") continue;
    let parsedArgs = {};
    try {
      parsedArgs = typeof call.argsJson === "string"
        ? JSON.parse(call.argsJson || "{}")
        : (call.args && typeof call.args === "object" ? call.args : {});
    } catch {
      parsedArgs = {};
    }
    const validation = validateToolCall(call.toolId.trim(), parsedArgs);
    if (validation.valid) {
      validCalls.push({
        toolId: call.toolId.trim(),
        rationale: String(call.rationale || "Planned by ULTRA gateway").slice(0, 240),
        argsJson: JSON.stringify(parsedArgs)
      });
    } else {
      logStructured("warn", "dropped_invalid_tool_call", {
        toolId: call.toolId,
        errors: validation.errors
      });
    }
  }
  return validCalls;
}

async function checkRateLimit(request, env) {
  if (!env.ULTRA_KV) return { allowed: true, remaining: null };
  const limit = Math.max(5, Number(env.RATE_LIMIT_PER_MIN || 45));
  const ip = request.headers.get("CF-Connecting-IP") || "anonymous";
  const minuteBucket = Math.floor(Date.now() / 60000);
  const key = `rl:${ip}:${minuteBucket}`;
  try {
    const currentRaw = await env.ULTRA_KV.get(key);
    const current = currentRaw ? parseInt(currentRaw, 10) || 0 : 0;
    if (current >= limit) {
      return { allowed: false, remaining: 0, limit };
    }
    await env.ULTRA_KV.put(key, String(current + 1), { expirationTtl: 120 });
    return { allowed: true, remaining: limit - current - 1, limit };
  } catch (err) {
    logStructured("warn", "kv_rate_limit_error", { message: err?.message || "kv error" });
    return { allowed: true, remaining: null };
  }
}

function isAuthorized(request, env) {
  const configuredToken = env.ULTRA_AUTH_TOKEN;
  if (!configuredToken) return true;
  const authHeader = request.headers.get("Authorization") || "";
  const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7).trim() : "";
  return token.length > 0 && token === configuredToken;
}

function buildSystemPrompt(language, toolsJson, screenContext) {
  const screenNote = screenContext ? `\nLive Android Screen UI:\n${String(screenContext).slice(0, 1800)}` : "";
  const toolsNote = toolsJson && toolsJson !== "[]" ? `\nAvailable Android Tools:\n${String(toolsJson).slice(0, 3500)}` : "";
  return (
    "You are ULTRA AI — a hybrid JARVIS Android Device Agent + ChatGPT conversational companion. " +
    `Preferred language: ${language || "English"}. Fluently understand Bengali (বাংলা), Hindi (हिन्दी), Banglish, Hinglish, and English. ` +
    "Think analytically step-by-step before replying. In 'thoughtSummary', briefly explain your reasoning, intent analysis, and context links. " +
    "Respond naturally and warmly like a human conversation, without unnecessary Markdown symbols. " +
    "Never claim you completed a device action unless you include a valid toolCall from the registered tools. " +
    "Set 'nextActionNeeded': true ONLY when the app must first open or transition so ULTRA can inspect the live screen next. " +
    "Return valid JSON only with shape: " +
    '{"thoughtSummary":"...","reply":"...","nextActionNeeded":false,"toolCalls":[{"toolId":"...","rationale":"...","argsJson":"{...}"}]}' +
    screenNote +
    toolsNote
  );
}

async function callWorkersAI(env, message, history, language, toolsJson, screenContext) {
  const model = env.CF_AI_MODEL || "@cf/meta/llama-3.1-8b-instruct";
  const messages = [
    { role: "system", content: buildSystemPrompt(language, toolsJson, screenContext) },
    ...history.map((h) => ({
      role: h.role === "model" ? "assistant" : h.role,
      content: h.content
    })),
    { role: "user", content: message }
  ];
  const res = await env.AI.run(model, {
    messages,
    max_tokens: 700,
    temperature: 0.45
  });
  const text = typeof res?.response === "string" ? res.response : JSON.stringify(res || {});
  return { provider: `cloudflare-ai:${model}`, rawText: text.trim() };
}

async function callOpenAI(env, message, history, language, toolsJson, screenContext) {
  const model = env.OPENAI_MODEL || "gpt-4o-mini";
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.OPENAI_API_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model,
      instructions: buildSystemPrompt(language, toolsJson, screenContext),
      input: [...history, { role: "user", content: message }],
      max_output_tokens: 650,
      store: false
    })
  });
  const raw = await response.text();
  let parsed = {};
  try {
    parsed = JSON.parse(raw);
  } catch {
    parsed = {};
  }
  if (!response.ok) {
    throw new Error(parsed?.error?.message || `OpenAI HTTP ${response.status}`);
  }
  let text = parsed.output_text || "";
  if (!text && Array.isArray(parsed.output)) {
    for (const item of parsed.output) {
      for (const part of item.content || []) {
        if (part.type === "output_text") text += part.text || "";
      }
    }
  }
  return { provider: `openai:${model}`, rawText: text.trim() };
}

async function callGeminiRest(env, message, history, language, toolsJson, screenContext) {
  const model = env.GEMINI_MODEL || "gemini-3.8-flash";
  const contents = [
    ...history.map((h) => ({
      role: h.role === "assistant" ? "model" : "user",
      parts: [{ text: h.content }]
    })),
    { role: "user", parts: [{ text: message }] }
  ];
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(env.GEMINI_API_KEY)}`;
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: buildSystemPrompt(language, toolsJson, screenContext) }] },
      contents,
      generationConfig: {
        responseMimeType: "application/json",
        maxOutputTokens: 700
      }
    })
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || `Gemini HTTP ${response.status}`);
  }
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text || "";
  return { provider: `gemini:${model}`, rawText: text.trim() };
}

function parseModelOutput(rawText) {
  if (!rawText) {
    return { thoughtSummary: "Empty output", reply: "Please try again.", nextActionNeeded: false, toolCalls: [] };
  }
  const cleaned = rawText.replace(/^```json\s*/i, "").replace(/```$/i, "").trim();
  try {
    const obj = JSON.parse(cleaned);
    return {
      thoughtSummary: String(obj.thoughtSummary || "Gateway reasoning complete").slice(0, 400),
      reply: String(obj.reply || "Ready.").trim(),
      nextActionNeeded: Boolean(obj.nextActionNeeded),
      toolCalls: normalizeAndFilterToolCalls(obj.toolCalls)
    };
  } catch {
    return {
      thoughtSummary: "Direct conversational response",
      reply: rawText.slice(0, 1800),
      nextActionNeeded: false,
      toolCalls: []
    };
  }
}

async function recordAuditSafe(env, entry) {
  if (!env.DB) return;
  try {
    await env.DB.prepare(
      "INSERT INTO audit_logs (id, user_id, tool_id, risk_level, status, summary, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)"
    )
      .bind(
        crypto.randomUUID(),
        String(entry.userId || "device"),
        String(entry.toolId || "voice_gateway"),
        String(entry.riskLevel || "LOW"),
        String(entry.status || "OK"),
        String(entry.summary || "").slice(0, 500),
        Date.now()
      )
      .run();
  } catch (err) {
    logStructured("warn", "d1_audit_write_failed", { message: err?.message || "d1 error" });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    if (url.pathname === "/health" && request.method === "GET") {
      return reply({
        ok: true,
        service: "ULTRA AI Voice & Agent Gateway",
        version: "2.2.0",
        d1Configured: Boolean(env.DB),
        kvConfigured: Boolean(env.ULTRA_KV),
        providers: {
          workersAi: Boolean(env.AI),
          openai: Boolean(env.OPENAI_API_KEY),
          gemini: Boolean(env.GEMINI_API_KEY)
        }
      });
    }

    if (!isAuthorized(request, env)) {
      return reply({ ok: false, error: "Unauthorized gateway request" }, 401);
    }

    if (url.pathname === "/api/tools" && request.method === "GET") {
      return reply({ ok: true, tools: TOOL_SCHEMAS });
    }

    if (url.pathname === "/api/tools/validate" && request.method === "POST") {
      let body;
      try {
        body = await request.json();
      } catch {
        return reply({ ok: false, error: "Invalid JSON body" }, 400);
      }
      const res = validateToolCall(String(body.toolId || "").trim(), body.args || {});
      return reply({ ok: true, ...res }, res.valid ? 200 : 422);
    }

    if (url.pathname === "/api/tasks" && request.method === "GET") {
      if (!env.DB) return reply({ ok: false, error: "D1 database (DB) is not bound" }, 503);
      const userId = url.searchParams.get("userId") || "default";
      const rows = await env.DB.prepare(
        "SELECT id, user_id, title, status, tool_id, created_at, updated_at FROM tasks WHERE user_id = ?1 ORDER BY updated_at DESC LIMIT 50"
      )
        .bind(userId)
        .all();
      return reply({ ok: true, tasks: rows.results || [] });
    }

    if (url.pathname === "/api/tasks" && request.method === "POST") {
      if (!env.DB) return reply({ ok: false, error: "D1 database (DB) is not bound" }, 503);
      let body;
      try {
        body = await request.json();
      } catch {
        return reply({ ok: false, error: "Invalid JSON body" }, 400);
      }
      const title = String(body.title || "").trim();
      if (!title) return reply({ ok: false, error: "Task title is required" }, 400);
      const now = Date.now();
      const id = crypto.randomUUID();
      await env.DB.prepare(
        "INSERT INTO tasks (id, user_id, title, status, tool_id, payload_json, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"
      )
        .bind(
          id,
          String(body.userId || "default"),
          title.slice(0, 200),
          String(body.status || "PENDING"),
          String(body.toolId || ""),
          JSON.stringify(body.payload || {}).slice(0, 2000),
          now,
          now
        )
        .run();
      return reply({ ok: true, id, status: "PENDING" }, 201);
    }

    if (url.pathname.startsWith("/api/tasks/") && request.method === "PATCH") {
      if (!env.DB) return reply({ ok: false, error: "D1 database (DB) is not bound" }, 503);
      const taskId = url.pathname.slice("/api/tasks/".length).trim();
      if (!taskId) return reply({ ok: false, error: "Task id is required" }, 400);
      let body;
      try {
        body = await request.json();
      } catch {
        return reply({ ok: false, error: "Invalid JSON body" }, 400);
      }
      const nextStatus = String(body.status || "COMPLETED").toUpperCase();
      if (!["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"].includes(nextStatus)) {
        return reply({ ok: false, error: "Invalid task status" }, 422);
      }
      const res = await env.DB.prepare(
        "UPDATE tasks SET status = ?1, updated_at = ?2 WHERE id = ?3"
      )
        .bind(nextStatus, Date.now(), taskId)
        .run();
      if ((res.meta?.changes || 0) === 0) {
        return reply({ ok: false, error: "Task not found" }, 404);
      }
      return reply({ ok: true, id: taskId, status: nextStatus });
    }

    if (url.pathname === "/api/audit" && request.method === "GET") {
      if (!env.DB) return reply({ ok: false, error: "D1 database (DB) is not bound" }, 503);
      const userId = url.searchParams.get("userId") || "device";
      const rows = await env.DB.prepare(
        "SELECT id, user_id, tool_id, risk_level, status, summary, created_at FROM audit_logs WHERE user_id = ?1 ORDER BY created_at DESC LIMIT 50"
      )
        .bind(userId)
        .all();
      return reply({ ok: true, logs: rows.results || [] });
    }

    if (url.pathname === "/api/audit" && request.method === "POST") {
      if (!env.DB) return reply({ ok: false, error: "D1 database (DB) is not bound" }, 503);
      let body;
      try {
        body = await request.json();
      } catch {
        return reply({ ok: false, error: "Invalid JSON body" }, 400);
      }
      await recordAuditSafe(env, {
        userId: "anonymous",
        toolId: String(body.toolId || "tool").slice(0, 64),
        riskLevel: String(body.riskLevel || "LOW").slice(0, 16),
        status: String(body.status || "OK").slice(0, 24),
        summary: "metadata_only"
      });
      return reply({ ok: true }, 201);
    }

    if (url.pathname !== "/api/voice" || request.method !== "POST") {
      return reply({ ok: false, error: "Not found" }, 404);
    }

    const rate = await checkRateLimit(request, env);
    if (!rate.allowed) {
      return reply({ ok: false, error: "Rate limit exceeded. Retry in a minute.", limit: rate.limit }, 429);
    }

    if (!env.AI && !env.OPENAI_API_KEY && !env.GEMINI_API_KEY) {
      logStructured("error", "missing_ai_providers");
      return reply({
        ok: false,
        error: "Configure Cloudflare Workers AI [ai] binding or GEMINI_API_KEY/OPENAI_API_KEY secret."
      }, 503);
    }

    let data;
    try {
      data = await request.json();
    } catch {
      return reply({ ok: false, error: "Invalid JSON" }, 400);
    }

    const message = String(data.message || "").trim();
    if (!message) return reply({ ok: false, error: "Message is empty" }, 400);
    if (message.length > 4000) return reply({ ok: false, error: "Message too long" }, 413);

    const language = String(data.language || "English").slice(0, 40);
    const history = Array.isArray(data.history)
      ? data.history
          .slice(-10)
          .filter((x) => x && ["user", "assistant", "model"].includes(x.role) && typeof x.content === "string")
          .map((x) => ({
            role: x.role === "model" ? "assistant" : x.role,
            content: x.content.slice(0, 1500)
          }))
      : [];

    const preferred = String(data.provider || env.DEFAULT_PROVIDER || "auto").toLowerCase();
    const attempts = [];
    if (preferred === "cloudflare") {
      if (env.AI) attempts.push("workersai");
      if (env.GEMINI_API_KEY) attempts.push("gemini");
      if (env.OPENAI_API_KEY) attempts.push("openai");
    } else if (preferred === "openai") {
      if (env.OPENAI_API_KEY) attempts.push("openai");
      if (env.GEMINI_API_KEY) attempts.push("gemini");
      if (env.AI) attempts.push("workersai");
    } else {
      if (env.GEMINI_API_KEY) attempts.push("gemini");
      if (env.OPENAI_API_KEY) attempts.push("openai");
      if (env.AI) attempts.push("workersai");
    }

    const toolsJson = typeof data.tools === "string" ? data.tools : JSON.stringify(data.tools || []);
    const screenContext = typeof data.screenContext === "string" ? data.screenContext : "";

    let lastError = null;
    for (const provider of attempts) {
      try {
        const result = provider === "gemini"
          ? await callGeminiRest(env, message, history, language, toolsJson, screenContext)
          : provider === "openai"
            ? await callOpenAI(env, message, history, language, toolsJson, screenContext)
            : await callWorkersAI(env, message, history, language, toolsJson, screenContext);
        const decision = parseModelOutput(result.rawText);
        return reply({
          ok: true,
          provider: result.provider,
          reply: decision.reply,
          thoughtSummary: decision.thoughtSummary,
          nextActionNeeded: decision.nextActionNeeded,
          toolCalls: decision.toolCalls,
          rateRemaining: rate.remaining
        });
      } catch (err) {
        lastError = err;
        logStructured("warn", "provider_attempt_failed", {
          provider,
          message: err?.message || "unknown error"
        });
      }
    }

    logStructured("error", "all_providers_failed", { message: lastError?.message || "unknown" });
    return reply({
      ok: false,
      error: "AI provider request failed",
      detail: String(lastError?.message || "Unknown error").slice(0, 300)
    }, 502);
  }
};
