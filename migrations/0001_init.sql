-- ULTRA AI Cloudflare D1 Schema (Migration 0001)
-- Compatible with Cloudflare D1 SQLite Free Plan

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  title TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  tool_id TEXT DEFAULT '',
  payload_json TEXT DEFAULT '{}',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tasks_user_updated
  ON tasks(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS audit_logs (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  tool_id TEXT NOT NULL,
  risk_level TEXT NOT NULL DEFAULT 'LOW',
  status TEXT NOT NULL DEFAULT 'OK',
  summary TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_user_created
  ON audit_logs(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS memory_snapshots (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  category TEXT NOT NULL,
  title TEXT NOT NULL,
  content_redacted TEXT NOT NULL,
  expires_at INTEGER,
  updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_user_updated
  ON memory_snapshots(user_id, updated_at DESC);
