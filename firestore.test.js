const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_ultra_1";
const BOB_UID = "bob_ultra_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any ULTRA AI user collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("memories").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_1").set({
      userId: ALICE_UID,
      category: "PREFERENCE",
      title: "Language",
      content: "English",
      encrypted: false,
      expiresAtMillis: 0,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("automations").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("audit_logs").get());
});

test("Authenticated owner: valid memory create, partial update, list, and delete succeed; invalid payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const memRef = aliceDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_1");

  await assertSucceeds(
    memRef.set({
      userId: ALICE_UID,
      category: "PREFERENCE",
      title: "Preferred Language",
      content: "Bengali",
      encrypted: false,
      expiresAtMillis: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("memories").get()
  );

  await assertSucceeds(
    memRef.update({
      content: "Bengali and English",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / @DocumentId id field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_2").set({
      id: "mem_2",
      userId: ALICE_UID,
      category: "CONTEXT",
      title: "Bad",
      content: "Contains id",
      encrypted: false,
      expiresAtMillis: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    memRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("memories").doc("mem_future").set({
      userId: ALICE_UID,
      category: "CONTEXT",
      title: "Future",
      content: "Clock skew test",
      encrypted: false,
      expiresAtMillis: 0,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  await assertSucceeds(memRef.delete());
});

test("Authenticated owner: valid automation & immutable audit_log succeed; cross-user access denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = new Date(Date.now() - 5000);

  const ruleRef = aliceDb.collection("users").doc(ALICE_UID).collection("automations").doc("rule_1");
  await assertSucceeds(
    ruleRef.set({
      userId: ALICE_UID,
      name: "Morning Briefing",
      description: "Open Chrome news",
      triggerType: "VOICE_PHRASE",
      actionTool: "open_app",
      actionArgsJson: "{\"appName\":\"Chrome\"}",
      enabled: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    ruleRef.update({
      enabled: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  const logRef = aliceDb.collection("users").doc(ALICE_UID).collection("audit_logs").doc("log_1");
  await assertSucceeds(
    logRef.set({
      userId: ALICE_UID,
      toolId: "open_app",
      riskLevel: "LOW",
      status: "SUCCESS",
      summary: "Opened Chrome",
      verified: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Audit logs are append-only (update denied)
  await assertFails(
    logRef.update({
      status: "DENIED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Cross-user isolation checks
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("automations").doc("rule_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("automations").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("audit_logs").doc("log_1").delete());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("memories").doc("spoof").set({
      userId: BOB_UID,
      category: "CONTEXT",
      title: "Spoof",
      content: "Cross user write",
      encrypted: false,
      expiresAtMillis: 0,
      createdAt: created,
      updatedAt: created,
    })
  );
});
