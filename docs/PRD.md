# SnapAct — PRD & 1-Hour Build Plan

> Android Dev Summit 2026 hackathon · **Track 2: in-app agents (ML Kit GenAI), fully on-device**
> Status: **v4** (reviewed by a Kotlin specialist and an architecture reviewer, see §13; cloud cut) · Repo: https://github.com/anshu7vyas/snapact-ads2026

**This is a hackathon prototype, not a production app.** Everything is sized for ~60 minutes of coding by **2–3 people** with one Pixel 11. If something is not on the demo path, cut it.

---

## 1. Problem & pitch

**Problem:** "We collect 50 business cards at an event; 90% never get followed up." Typing contacts, writing emails and making invites is friction nobody tackles at a booth.

**Pitch:** Photograph a card and add an optional note ("met at booth, wants a demo next Tuesday"). An **on-device agent** (Gemini Nano via ML Kit GenAI) reads the card and **calls the app's tools**: save the contact, draft a personalized email, schedule the follow-up, show the company on a map, find the person on LinkedIn. **The agent plans and calls tools. Irreversible actions need the user's consent. Nothing leaves the phone, ever.**

**Why on-device:** business cards are PII, conference Wi-Fi is bad, and inference has no per-call cost.

## 2. Track 2 fit & judging

Track 2 deck (p.13–17): *"Empowered by ML Kit GenAI and AICore, build in-app agents that run fully on-device."* Building blocks: **AppFunctions → Agent → AgentRunner → AgentEvent**.

| Judging criterion | How SnapAct hits it |
|---|---|
| **Agentic integration** — "an agent takes real action through your app" | Nano's `AgentRunner` calls our `@AppFunction` tools. Action cards **stream in as each tool call happens** and fire real Contacts, Gmail, Calendar and Maps intents. The live trace shows `AgentEvent`s. |
| **User impact & value** | One photo becomes a finished follow-up with no typing. Everyone at the event has this problem. |
| **Creativity & innovation** | The full agent loop running **in airplane mode ✈️**. |
| Tech named in the deck | ML Kit Text Recognition v2, Entity Extraction, the GenAI Agent and Prompt APIs, AppFunctions |

**Honesty rule:** the rules step (R3) is a safety net and is **never presented as the agent**.

### Honorable mention (not built): cloud fallback
Firebase AI Logic with `InferenceMode.PREFER_ON_DEVICE` or cloud function calling (the starter's `runAgent()`) could handle hard cards when online. It is **cut to save time** (no Firebase project, no `google-services.json`, no extra fallback step) and **mentioned in the pitch as the next step**. The starter's cloud code stays in git history.

## 3. Architecture

```
 Camera/Gallery bitmap + note
          │
          ▼
 ┌──────────────────────────────────────┐  PERCEIVE — WS2
 │ ML Kit Text Recognition v2 (OCR)     │──► rawText
 │ ML Kit Entity Extraction             │──► emails, phones, addresses, urls (card text)
 │                                      │──► noteDateTimeMs   (NOTE ONLY, referenceTime = now)
 │ name/company/title heuristic         │──► best-guess name, company, title
 └──────────────────────────────────────┘
          │ CardFacts
          ▼
 ┌──────────────────────────────────────┐  DECIDE — run top-down; ActionQueue.reset() before each step
 │ R1  AgentRunner + @AppFunction  🔒   │  timeout 15 s  ← hero (WS3)
 │ R1b Prompt API JSON planner     🔒   │  timeout 10 s  ← on-device backup (WS3)
 │ R3  Rule planner                ⚙️   │  deterministic safety net (WS2)
 └──────────────────────────────────────┘
          │ every step → ActionQueue.add(Action)   (validated, deduped by tool)
          ▼
 Cards stream in → "Do it" / "Approve all" → execute(ctx, action) → Android intent  (WS1)
```

**Key design choices**
1. **One action sink.** Every step writes to `ActionQueue`, and the UI collects only `ActionQueue.actions`. R1 tools enqueue from the AppFunction service and return `"queued for user approval"`. R1b and R3 return lists that the orchestrator enqueues.
2. **Reset plus dedupe.** `reset()` bumps `runId` and clears the list, and `add()` drops anything from an old run or a tool that's already queued. A late tool call from a cancelled R1 can't produce duplicate cards.
3. **The agent gets text, not an image.** The `AgentRunner` docs only show text `Content`, so grounded `CardFacts` go into the prompt.
4. **Entity Extraction handles the dates.** The datetime comes from the **note only**, with the reference time set to now. If the granularity is DAY, the time is set to 10:00.
5. **Validate before enqueueing.** Drop actions that are missing required args, and fill missing contact fields from `CardFacts`.
6. **Do not set `android:process`** on the AppFunction service, so `ActionQueue` is shared in-process.

## 4. Functional requirements

**P0** = demo-critical · **P1** = if on schedule · **P2** = stretch

| ID | Requirement | Pri | WS |
|---|---|---|---|
| FR-1 | Capture a card with the camera or gallery; optional note; portrait lock | P0 | WS1 |
| FR-2 | Action cards stream in from `ActionQueue`, with "Do it" → intent (existing `execute()`) | P0 | WS1 |
| FR-3 | Live trace + step badge `🔒 On-device agent` / `🔒 On-device planner` / `⚙️ Rules` | P0 | WS1 |
| FR-4 | Ladder orchestrator: per-step timeout, rethrow `CancellationException`, a step counts only with ≥1 valid action | P0 | WS1 |
| FR-5 | **On-device OCR**, ML Kit Text Recognition v2 | P0 | WS2 |
| FR-6 | **On-device Entity Extraction** (card: email, phone, address, URL; note: datetime) | P0 | WS2 |
| FR-7 | Name/company/title heuristic | P0 | WS2 |
| FR-8 | **Rule planner (R3)** | P0 | WS2 |
| FR-9 | **5 `@AppFunction` tools** with agent-centric KDoc → `ActionQueue.add` | P0 | WS3 |
| FR-10 | **On-device agent (R1):** `AgentRunner` created once at start, `runSingleTurnAsync`, `AgentEvent`s → trace | P0 | WS3 |
| FR-11 | **On-device planner (R1b):** Prompt API → JSON `[{tool,args}]` → `List<Action>` | P1 | WS3 |
| FR-12 | "Approve all" button · "Force step" debug chips | P1 | WS1 |
| FR-13 | Edit args · email tone via the GenAI Rewriting API · cloud fallback (honorable mention) | P2 | — |

## 5. Non-functional requirements (hackathon-sized)

| ID | Requirement | Target |
|---|---|---|
| NFR-1 **Privacy** | **Nothing leaves the phone.** There are no network calls in the app (the one-time ML Kit model download happens before the demo). | Hard rule — it's the pitch |
| NFR-2 **Offline** | The full flow works in airplane mode | Shown live |
| NFR-3 **Latency** | Perception < 1.5 s; R1 first card < 5 s when warm; trace streams per event | `AgentRunner` warmed at start; `ActionQueue.reset()` after any warm-up |
| NFR-4 **Resilience** | Fallthrough on `GenAiException` (`NOT_AVAILABLE`/`BUSY`/`MAX_TURNS_EXCEEDED`), timeout, or zero valid actions | R1 15 s · R1b 10 s |
| NFR-5 **Safety** | The agent can only enqueue. Every write needs a tap, and every intent opens the system app to confirm. `setMaxTurns(5)` | Hard rule |
| NFR-6 **Device** | Pixel 11 (AICore: **Gemini Nano 4 Fast [TPU]** ✅), **minSdk 34** | |
| NFR-7 **Foreground** | Inference only in the foreground; the ladder runs only after returning from the camera | |
| NFR-8 **Public repo** | No secrets committed. With no Firebase there's no `google-services.json` (still gitignored) | Hard rule |
| NFR-9 **Scope** | One module, about 7 small files, no DI/Room/tests; the manual demo run is the test | Prototype |

**Out of scope:** cloud/Firebase, history, auth, batch scanning, CRM sync, automated tests, accessibility audit, release/ProGuard, non-Latin cards, exposing tools to the Gemini app (Track 1).

## 6. Shared contract (lands in PR #0)

```kotlin
// Model.kt
data class Action(val tool: String, val args: Map<String, String>)
data class CardFacts(
    val rawText: String,
    val name: String?, val company: String?, val title: String?,
    val emails: List<String>, val phones: List<String>,
    val addresses: List<String>, val urls: List<String>,
    val noteDateTimeMs: Long?,
)
enum class Rung { ON_DEVICE_AGENT, ON_DEVICE_PLANNER, RULES }

object ActionQueue {
    private val _actions = MutableStateFlow<List<Action>>(emptyList())
    val actions: StateFlow<List<Action>> = _actions
    @Volatile var runId = 0L; private set
    fun reset() { runId++; _actions.value = emptyList() }
    /** Drops stale-run and duplicate-tool actions; one action per tool in this app. */
    fun add(a: Action, forRun: Long = runId) {
        if (forRun != runId) return
        _actions.update { list -> if (list.any { it.tool == a.tool }) list else list + a }
    }
}

// Perception.kt + RulePlanner.kt  (WS2)
suspend fun perceive(card: Bitmap, note: String, onStep: (String) -> Unit): CardFacts
fun rulePlan(facts: CardFacts): List<Action>
// SnapActTools.kt + OnDeviceAgent.kt  (WS3)
suspend fun onDeviceAgentPlan(facts: CardFacts, note: String, onStep: (String) -> Unit)   // enqueues via tools
suspend fun onDevicePlannerPlan(facts: CardFacts, note: String, onStep: (String) -> Unit): List<Action>
// Actions.kt + MainActivity.kt  (WS1) — execute(), label(), loadBitmap(), UI, runLadder()
```

Tool names (`save_contact`, `draft_followup_email`, `schedule_followup`, `find_on_linkedin`, `show_on_map`) and their args stay as they are in the starter.

**Ladder sketch (WS1):**
```kotlin
suspend fun runLadder(card: Bitmap, note: String, forced: Rung?, onStep: (String) -> Unit): Rung {
    val facts = perceive(card, note, onStep)
    suspend fun attempt(rung: Rung, ms: Long, block: suspend () -> List<Action>?): Boolean {
        if (forced != null && forced != rung) return false
        ActionQueue.reset(); val run = ActionQueue.runId
        try {
            withTimeoutOrNull(ms) { block() }?.filter(::isValid)?.forEach { ActionQueue.add(it, run) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { onStep("⚠️ $rung failed: ${e.message}") }
        return ActionQueue.actions.value.isNotEmpty()
    }
    if (attempt(Rung.ON_DEVICE_AGENT, 15_000) { onDeviceAgentPlan(facts, note, onStep); null }) return Rung.ON_DEVICE_AGENT
    if (attempt(Rung.ON_DEVICE_PLANNER, 10_000) { onDevicePlannerPlan(facts, note, onStep) }) return Rung.ON_DEVICE_PLANNER
    ActionQueue.reset(); rulePlan(facts).forEach(ActionQueue::add); return Rung.RULES
}
```

## 7. Dependencies

> **As built in PR #0 (verified on the Pixel 11 Pro):** AGP 9.1.1 · Gradle 9.3.1 · Kotlin 2.3.20 · KSP 2.3.12 · compileSdk 37 · targetSdk 36 · minSdk 34 · `entity-extraction 16.0.0-beta6`. `appfunctions alpha13` *requires* AGP ≥ 9.1 and compileSdk ≥ 37. The versions in `gradle/libs.versions.toml` are the source of truth; the block below is the original plan.

```kotlin
// app/build.gradle.kts — no Firebase
implementation("com.google.mlkit:text-recognition:16.0.1")            // on-device OCR (bundled)
implementation("com.google.mlkit:entity-extraction:16.0.0-beta5")     // on-device entities
implementation("com.google.mlkit:genai-agent:1.0.0-alpha1")           // AgentRunner (Nano)
implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")           // Prompt API (R1b)
implementation("androidx.appfunctions:appfunctions:1.0.0-alpha13")    // @AppFunction tools
ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha13")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:<latest>")             // R1b JSON
implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:<latest>"))
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services")                // Task.await()
// plugins: Kotlin 2.3.x, org.jetbrains.kotlin.plugin.compose 2.3.x, plugin.serialization 2.3.x,
//          com.google.devtools.ksp (latest KSP2 for 2.3.x). AGP 9 has Kotlin built in — if
//          kotlin-android is rejected, remove it.
```
Manifest: the FileProvider (`docs/SETUP.md` §4–5) + the **AppFunctions service entry** (`BIND_APP_FUNCTION_SERVICE`, action `android.app.appfunctions.AppFunctionService`; copy it from the ML Kit "define tools" page).

## 8. Work breakdown: 3 parallel PRs

**Rule:** PR #0 (the base) merges first. After that, the three workstreams touch **separate files** and each opens its own PR against `main`. Each PR must pass `./gradlew assembleDebug`. Only WS1 edits Gradle files, the manifest, `Model.kt` and `MainActivity.kt`. If you need a dependency or manifest change, ask WS1. Rebase on `main` before opening your PR.

```
T-10 ──► PR #0 base (WS1 + WS3 pair) ──► merge
                     │
         ┌───────────┼─────────────────┐
         ▼           ▼                 ▼
      PR #1 WS1    PR #2 WS2         PR #3 WS3        (parallel; merge as each goes green)
      app/UI       perception+rules  on-device agent
         └───────────┴────────┬────────┘
                              ▼
                 T+45 freeze → integration run on Pixel 11
```

### PR #0 — Base scaffold · `chore/base` · owners WS1 + WS3 (pair, ~10 min, before the clock if allowed)
- Android Studio "Empty Activity" (Compose) project at the repo root; **minSdk 34**; package `com.example.snapact` (or the team's choice; match the FileProvider authority).
- All §7 dependencies and plugins; FileProvider + AppFunctions manifest entries; portrait lock.
- `Model.kt` exactly as in §6.
- **Stub files** that compile, so each workstream only replaces bodies:
  ```kotlin
  // Perception.kt
  suspend fun perceive(card: Bitmap, note: String, onStep: (String) -> Unit) =
      CardFacts("", null, null, null, emptyList(), emptyList(), emptyList(), emptyList(), null)
  // RulePlanner.kt
  fun rulePlan(facts: CardFacts): List<Action> = listOf(Action("save_contact", mapOf("name" to "Stub")))
  // OnDeviceAgent.kt
  suspend fun onDeviceAgentPlan(facts: CardFacts, note: String, onStep: (String) -> Unit) {}
  suspend fun onDevicePlannerPlan(facts: CardFacts, note: String, onStep: (String) -> Unit): List<Action> = emptyList()
  // SnapActTools.kt — one trivial @AppFunctionDeclaration so KSP runs
  ```
- Starter `MainActivity.kt` split: `execute()`/`label()`/`loadBitmap()` → `Actions.kt`; **the Firebase code is deleted**.
- ✅ Done when the app builds, installs on the Pixel 11, and tapping Run shows the stub "Save contact" card.
- ⚠️ If KSP/AppFunctions won't compile in 10 minutes: remove those deps, merge without them, and WS3 goes straight to R1b.

### PR #1 — WS1: App shell, UI & orchestration · `feat/app-ui`
**Files:** `MainActivity.kt`, `Actions.kt`, `Ladder.kt` (+ Gradle, manifest and `Model.kt` on request)
- [ ] `runLadder()` (§6) wired to the Run button inside the existing `scope.launch`
- [ ] UI collects `ActionQueue.actions.collectAsState()`; cards stream in; `done` keyed by `tool`
- [ ] Trace panel + step badge (FR-3); rethrow `CancellationException` (fix the starter's catch-all)
- [ ] `isValid(action)`: `draft_followup_email` needs `to`; `schedule_followup` needs a parseable `start`; `save_contact` needs `name`
- [ ] P1: "Approve all"; "Force step" chips (Agent / Planner / Rules) feeding `forced`
- [ ] Device setup: Gmail, Contacts, Calendar and Maps signed in; fire each of the 5 intents once
- **Works on an emulator** against stubs. ✅ Done when the stub run streams cards and every intent opens.

### PR #2 — WS2: On-device perception & rules · `feat/perception`
**Files:** `Perception.kt`, `RulePlanner.kt`
- [ ] OCR: `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0)).await().text`
- [ ] Entity Extraction: one shared `EntityExtractor` (ENGLISH); `downloadModelIfNeeded()`; run on the **card text** for EMAIL/PHONE/ADDRESS/URL (use `annotation.annotatedText` for values) and on the **note only** for `TYPE_DATE_TIME` with `setReferenceTime(now)` + `setReferenceTimeZone(default)`; DAY granularity → 10:00
- [ ] Name/company/title heuristic: name = first OCR line with 2–3 capitalized words and no digits or `@`; company = a line containing the email domain stem, otherwise the line after the name; title = a line with Engineer/Manager/Director/Founder/VP/Lead…
- [ ] `onStep` trace lines: `🔒 OCR: 412 chars`, `🔒 Entities: 1 email, 1 phone, 1 address`, `🔒 Note date: Tue 10:00`
- [ ] `rulePlan()`: `save_contact` always; email → `draft_followup_email` (template using name + note); address → `show_on_map`; name + company → `find_on_linkedin`; `schedule_followup` at note datetime, otherwise +2 days at 10:00
- [ ] Before the demo: entity model downloaded on Wi-Fi, then checked in **airplane mode**; 2–3 well-lit sample cards in the gallery
- **Works on an emulator.** ✅ Done when a real card gives correct `CardFacts` and R3 produces sensible cards.

### PR #3 — WS3: On-device agent · `feat/on-device-agent`
**Files:** `SnapActTools.kt`, `OnDeviceAgent.kt`
- [ ] Smoke test first: `AgentRunner(Agent.builder().setName("SnapAct").build()).runSingleTurnAsync(text "hi")` returns text on the Pixel 11
- [ ] `SnapActTools : AppFunctionService` with 5 `@AppFunctionDeclaration(isDescribedByKDoc = true)` functions mirroring the starter's tool names and args; each one calls `ActionQueue.add(...)` and returns `"queued for user approval"`. Use agent-centric KDoc as the prompt (e.g. "Call when the card has an email address…")
- [ ] Log `Process.myPid()` in a tool and in the Activity; they must match
- [ ] `onDeviceAgentPlan()`: one `AgentRunner` created lazily and warmed; `Agent.builder().setInstructions(<starter system prompt>).enableAppFunctions(true).setMaxTurns(5)`; prompt = `CardFacts` + note + `LocalDateTime.now()`; `AgentEvent.FunctionCall` → `onStep("→ name(args)")`, `TextMessage` (non-partial) → `onStep("✅ …")`, `else -> {}`
- [ ] If tools aren't discovered: `AppFunctionAgentTools.discover(...)` + `addTools(...)`
- [ ] **T+25 cut:** no tool call yet → build `onDevicePlannerPlan()` (R1b): Prompt API `Generation.getClient()`, ask for JSON `[{"tool":…,"args":{…}}]` over the 5 tools, parse with `kotlinx.serialization` inside `runCatching`
- [ ] Before the freeze: **screen-record a good 🔒 run**
- **Needs the Pixel 11** (it has the device until T+25, then WS1 integrates). ✅ Done when a real card produces ≥3 tool calls in the trace.

### With only 2 people
- **Person 1** = WS1 + WS2 (app + perception + rules: the guaranteed demo floor).
- **Person 2** = WS3 (on-device agent, with R1b as the fallback).

## 9. Timeline

| Time | WS1 — App/UI | WS2 — Perception | WS3 — Agent |
|---|---|---|---|
| T-10 | PR #0 with WS3 | Device gate: accounts, sample cards, model download | PR #0 with WS1 |
| T+0–10 | Ladder against stubs | OCR on a sample card | Smoke test + PID check |
| T+10–25 | Streaming cards, badge, `isValid` | Entities + name heuristic | 5 tools + agent → first tool call |
| **T+25** | **PR #1 open** | **PR #2 open** | **Cut:** no tool call → R1b |
| T+25–45 | Approve all, force chips, intent checks | `rulePlan`, airplane-mode check | Tune KDoc/prompt; **PR #3 open** |
| T+45–55 | **Freeze.** Merge all, full run ×3 on the Pixel 11 | Airplane-mode run | Screen-record; force each step |
| T+55–60 | Buffer | | |

## 10. Demo script (2 min)

1. **Problem (15 s):** "50 cards, 90% never followed up, and they're full of personal data."
2. **Airplane mode on ✈️.** Scan a real card; note: "Met at booth, wants a demo next Tuesday."
3. **Run:** the trace streams `🔒 OCR → entities → Gemini Nano agent → saveContact → draftFollowupEmail → scheduleFollowup(Tue 10:00) → showOnMap`, and the cards appear as each tool fires.
4. **Offline taps:** contact saved → calendar invite **on Tuesday** → email draft.
5. **Back online:** tap map and LinkedIn (they need network).
6. **Close:** "One photo, no typing, nothing left the phone. The agent plans and calls tools; you consent to anything irreversible. Next step: a Firebase AI Logic hybrid fallback for hard cards."

## 11. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| KSP / AppFunctions / Kotlin 2.3 build friction | **Med-High** | PR #0 timebox; R1b needs no KSP |
| `enableAppFunctions` doesn't find our tools / service runs out-of-process | Med | PID check; `discover()` + `addTools()`; then R1b |
| Nano tool-call quality | Med | Grounded `CardFacts`; `isValid()`; fill missing args from facts |
| One device, 3 people | High | WS1 and WS2 on an emulator; WS3 has the Pixel until T+25 |
| Entity model missing offline | Med | Download before the demo; airplane-mode check |
| Missing intent targets / accounts | Med | WS1 fires all 5 intents early |
| PR merge conflicts | Low | Separate files; only WS1 touches shared files; stubs in PR #0 |

## 12. Open questions
- Coding time: the deck says **2 hours**; this plan assumes 60 minutes. With 2 hours, R1b becomes P0 and FR-13 (minus cloud) comes into scope.

## 13. Review log

| Reviewer | Key findings applied |
|---|---|
| Kotlin specialist | Atomic `ActionQueue` (`runId` + dedupe); rethrow `CancellationException`; pre-validated base commit with stubs; minSdk 34; Entity Extraction reference time, note-only datetime, DAY → 10:00; one warmed `AgentRunner`; R1 15 s timeout; force-step chips; portrait lock; key `done` by tool; validate args |
| Architecture review | R1b Prompt-API planner as the on-device agentic backup; R3 never pitched as the agent; `name/company/title` in `CardFacts`; airplane-mode demo order; streaming cards + "Approve all"; device slots + emulator; T+25 cut line; screen-record a good run |
| v4 (team decision) | **Cloud rung cut** → honorable mention; Firebase removed; work split into 3 parallel PRs |
