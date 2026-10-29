# SnapAct — PRD & 1-Hour Build Plan

> Android Dev Summit 2026 hackathon · **Track 2: in-app agents with ML Kit GenAI + Firebase AI Logic**
> Status: **v3, reviewed** (Kotlin specialist + architecture review, see §12) · Repo: https://github.com/anshu7vyas/snapact-ads2026

**This is a hackathon prototype, not a production app.** Everything is sized for ~60 minutes of coding by 3–4 people with one Pixel 11. If something is not on the demo path, cut it.

---

## 1. Problem & pitch

**Problem:** "We collect 50 business cards at an event; 90% never get followed up." Typing contacts, writing emails and making invites is friction nobody tackles at a booth.

**Pitch:** Photograph a card and add an optional note ("met at booth, wants a demo next Tuesday"). An **on-device agent** (Gemini Nano via ML Kit GenAI) reads the card and **calls the app's tools**: save the contact, draft a personalized email, schedule the follow-up, show the company on a map, find the person on LinkedIn. **The agent plans and calls tools. Irreversible actions need the user's consent.** On the on-device path, nothing leaves the phone.

**Why on-device:** business cards are PII, conference Wi-Fi is bad, and inference has no per-call cost.

## 2. Track 2 fit & judging

Track 2 deck (p.13–17): *"Empowered by ML Kit GenAI and AICore, build in-app agents that run fully on-device."* Building blocks: **AppFunctions → Agent → AgentRunner → AgentEvent**, with **Firebase AI Logic** for cloud agents.

| Judging criterion | How SnapAct hits it |
|---|---|
| **Agentic integration** — "an agent takes real action through your app" | Nano's `AgentRunner` calls our `@AppFunction` tools. Action cards **stream in as each tool call happens** and fire real Contacts, Gmail, Calendar and Maps intents. The live trace shows `AgentEvent`s. |
| **User impact & value** | One photo becomes a finished follow-up with no typing. Everyone at the event has this problem. |
| **Creativity & innovation** | The agent loop running **in airplane mode ✈️**. |
| Tech named in the deck | ML Kit Text Recognition v2, Entity Extraction, the GenAI Agent and Prompt APIs, AppFunctions, and Firebase AI Logic. |

**Honesty rule for the pitch:** the rules rung (R3) is a safety net and is **never presented as the agent**. If neither on-device rung works, we demo the cloud agent (R2) as the agent and say so.

## 3. Architecture

### 3.1 Pipeline

```
 Camera/Gallery bitmap + note
          │
          ▼
 ┌──────────────────────────────────────┐  PERCEIVE — on-device, any device
 │ ML Kit Text Recognition v2 (OCR)     │──► rawText
 │ ML Kit Entity Extraction             │──► emails, phones, addresses, urls (card text)
 │                                      │──► noteDateTimeMs   (NOTE ONLY, referenceTime = now)
 │ name/company/title heuristic         │──► best-guess name, company, title
 └──────────────────────────────────────┘
          │ CardFacts
          ▼
 ┌──────────────────────────────────────┐  DECIDE — run top-down; ActionQueue.reset() before each rung
 │ R1  ML Kit GenAI AgentRunner  🔒     │  Agent + @AppFunction tools        timeout 15 s  ← hero
 │ R1b ML Kit Prompt API planner 🔒     │  Nano returns JSON [{tool,args}]   timeout 10 s  ← offline agentic backup
 │ R2  Firebase AI Logic cloud   ☁️     │  starter runAgent() function calls timeout 8 s, skipped when offline
 │ R3  Rule planner              ⚙️     │  deterministic (safety net, never pitched)
 └──────────────────────────────────────┘
          │ every rung → ActionQueue.add(Action)   (validated, deduped by tool)
          ▼
 Cards stream in → "Do it" / "Approve all" → execute(ctx, action) → Android intent
```

### 3.2 Key design choices
1. **One action sink.** Every rung writes to `ActionQueue`, and the UI collects only `ActionQueue.actions`. R1 tools enqueue from the AppFunction service; R1b, R2 and R3 return lists that the orchestrator enqueues. The tools return `"queued for user approval"`, just like the starter's `runAgent()`.
2. **Reset plus dedupe on fallthrough.** `ActionQueue.reset()` bumps a `runId` and clears the list, and `add()` drops anything from an old run or a tool that's already queued. A late tool call from a cancelled R1 can't produce duplicate cards.
3. **The agent gets text, not an image.** The `AgentRunner` docs only show text `Content`. Perception runs first, and grounded `CardFacts` go into the prompt, which makes it less likely to invent fields.
4. **Entity Extraction handles the dates.** "Next Tuesday" is extracted from the **note only**, with the reference time set to now. Dates printed on the card are ignored. If the granularity is DAY, the time is set to 10:00.
5. **Validate before enqueueing.** Drop actions that are missing required args (`to` for email, a parseable `start` for calendar), and fill missing contact fields from `CardFacts`.
6. **Tools restricted to our own app.** `@AppFunctionAccessLevel(ACCESS_LEVEL_SELF)` if it compiles on alpha13; otherwise leave it out (only we call the tools anyway). Do **not** set `android:process` on the service, so `ActionQueue` is shared in-process.

### 3.3 Device gate (T-10)
- [x] AICore shows **"Gemini Nano 4 Fast [TPU]"** ✅. That points to Developer Preview access, so R1 is the main path.
- [ ] Device is signed into a Google account with **Gmail, Contacts, Calendar and Maps** set up. Fire each of the 5 intents once.
- [ ] Entity Extraction English model downloaded (`downloadModelIfNeeded()` on Wi-Fi), then **checked in airplane mode**.
- [ ] 2–3 well-lit sample cards pre-loaded in the gallery (glossy cards plus venue lighting defeat OCR).
- [ ] Cloud model name (`gemini-2.5-flash` in the starter) still served in the Firebase console. Change it if it has been retired.

## 4. Functional requirements

**P0** = demo-critical · **P1** = if on schedule · **P2** = stretch

| ID | Requirement | Pri | Owner |
|---|---|---|---|
| FR-1 | Capture a card with the camera or gallery; optional note (existing). Lock portrait so the camera intent doesn't recreate the activity. | P0 | A |
| FR-2 | **On-device OCR** with ML Kit Text Recognition v2, `InputImage.fromBitmap(bmp, 0)` on the starter's software bitmap of ~1600 px | P0 | B |
| FR-3 | **On-device Entity Extraction**: email, phone, address and URL from the card; datetime from the note only | P0 | B |
| FR-4 | **Name/company/title heuristic**: name = first OCR line with 2–3 capitalized words and no digits or `@`; company = a line containing the email domain stem, otherwise the line after the name | P0 | B |
| FR-5 | **On-device agent (R1):** `Agent.builder().enableAppFunctions(true).setMaxTurns(5)`; `AgentRunner` created once at app start; `runSingleTurnAsync(prompt)`; map `AgentEvent`s to the trace | P0 | C |
| FR-6 | **5 `@AppFunction` tools** with agent-centric KDoc (`isDescribedByKDoc = true`) that call `ActionQueue.add` | P0 | C |
| FR-7 | **Cloud agent (R2):** the starter `runAgent()` (image input) working first and tagged `golden` | P0 | D |
| FR-8 | Ladder orchestrator with per-rung timeout, `CancellationException` rethrown, R2 skipped when offline, a rung counts only if it produced ≥1 valid action | P0 | A |
| FR-9 | Action cards stream in live, each with "Do it"; trace + **rung badge** `🔒 On-device agent` / `🔒 On-device planner` / `☁️ Cloud` / `⚙️ Rules` | P0 | A |
| FR-10 | **Rule planner (R3):** `save_contact` always; email → `draft_followup_email` from a template; address → `show_on_map`; name + company → `find_on_linkedin`; note datetime → `schedule_followup`, otherwise +2 days at 10:00 | P0 | B |
| FR-11 | **On-device planner (R1b):** Prompt API (`genai-prompt`), ask for a JSON `[{tool,args}]` over the same 5 tools, parse with `kotlinx.serialization` inside `runCatching`. No `@Generable`/KSP needed. | P1 | C (B if C is blocked) |
| FR-12 | **"Approve all"** button; read-only actions (map, LinkedIn) marked as safe | P1 | A |
| FR-13 | Debug "Force rung" selector (4 chips) for rehearsal, and on stage if needed | P1 | A |
| FR-14 | R2 sends `CardFacts` text instead of the image (privacy improvement; keep the image path as a fallback) | P1 | D |
| FR-15 | Edit args before running · email tone via the GenAI Rewriting API · Nano multimodal card parse | P2 | — |

## 5. Non-functional requirements (hackathon-sized)

| ID | Requirement | Target |
|---|---|---|
| NFR-1 **Privacy** | **On-device path (🔒): nothing leaves the phone.** The cloud fallback runs only when on-device is unavailable, sends extracted text (P1) rather than the photo, and is labelled ☁️. "Nothing left the phone" may only be said on stage while the badge shows 🔒. | Hard rule |
| NFR-2 **Offline** | Perception + R1/R1b + R3 work in airplane mode, and R2 is skipped instantly when offline | Shown live |
| NFR-3 **Latency** | Perception < 1.5 s; R1 first card < 5 s when warm; trace streams per event | `AgentRunner` created and warmed at start; `ActionQueue.reset()` after any warm-up ping |
| NFR-4 **Resilience** | Fallthrough on `GenAiException` (`NOT_AVAILABLE`/`BUSY`/`MAX_TURNS_EXCEEDED`), timeout, or zero valid actions. Never catch `CancellationException`. | R1 15 s · R1b 10 s · R2 8 s |
| NFR-5 **Safety** | The agent can only enqueue. Every write needs a tap, and every intent opens the system app to confirm. `setMaxTurns(5)` | Hard rule |
| NFR-6 **Device** | Pixel 11, **minSdk 34** (avoids manifest-merge fights with the alpha libraries; the agent needs API 34+ anyway) | |
| NFR-7 **Foreground** | Inference only in the foreground (`BACKGROUND_USE_BLOCKED` otherwise); the ladder runs only after returning from the camera | |
| NFR-8 **Secrets** | `google-services.json` gitignored (already done); no keys in the **public** repo | Hard rule |
| NFR-9 **Scope** | One module, about 7 small files, no DI/Room/tests; the manual demo run is the test | Prototype |

**Out of scope:** history, auth, batch scanning, CRM sync, automated tests, accessibility audit, release/ProGuard, non-Latin cards, exposing tools to the Gemini app (Track 1).

## 6. Shared contract — in the base commit at T+0, with stubs

```kotlin
// Model.kt (A)
data class Action(val tool: String, val args: Map<String, String>)
data class CardFacts(
    val rawText: String,
    val name: String?, val company: String?, val title: String?,
    val emails: List<String>, val phones: List<String>,
    val addresses: List<String>, val urls: List<String>,
    val noteDateTimeMs: Long?,
)
enum class Rung { ON_DEVICE_AGENT, ON_DEVICE_PLANNER, CLOUD, RULES }

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

// Perception.kt (B)
suspend fun perceive(card: Bitmap, note: String, onStep: (String) -> Unit): CardFacts
fun rulePlan(facts: CardFacts): List<Action>
// SnapActTools.kt (C) — @AppFunctionServiceEntryPoint abstract class SnapActTools : AppFunctionService()
// OnDeviceAgent.kt (C)
suspend fun onDeviceAgentPlan(facts: CardFacts, note: String, onStep: (String) -> Unit)   // enqueues via tools
suspend fun onDevicePlannerPlan(facts: CardFacts, note: String, onStep: (String) -> Unit): List<Action>
// CloudAgent.kt (D) — the starter's toolDecls, model and runAgent() move here unchanged
suspend fun cloudPlan(card: Bitmap, facts: CardFacts, note: String, onStep: (String) -> Unit): List<Action>
// Actions.kt (A) — the starter's execute(), label() and loadBitmap() move here
// MainActivity.kt (A) — UI + runLadder()
```

**Ladder sketch (A):**
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
    if (isOnline() && attempt(Rung.CLOUD, 8_000) { cloudPlan(card, facts, note, onStep) }) return Rung.CLOUD
    rulePlan(facts).forEach(ActionQueue::add); return Rung.RULES
}
```
UI: `val actions by ActionQueue.actions.collectAsState()`. Key `done` on `action.tool`, not the list index. Fix the starter's `catch (e: Exception)` so it rethrows `CancellationException`.

## 7. Dependencies & base commit (T-10, owner A + C, on one machine)

```kotlin
// app/build.gradle.kts — in addition to docs/SETUP.md (Firebase AI Logic)
implementation("com.google.mlkit:text-recognition:16.0.1")            // on-device OCR (bundled)
implementation("com.google.mlkit:entity-extraction:16.0.0-beta5")     // on-device entities
implementation("com.google.mlkit:genai-agent:1.0.0-alpha1")           // AgentRunner (Nano)
implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")           // Prompt API (R1b)
implementation("androidx.appfunctions:appfunctions:1.0.0-alpha13")    // @AppFunction tools
ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha13")
implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:<latest>"))
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services") // Task.await()
// plugins: Kotlin 2.3.x + org.jetbrains.kotlin.plugin.compose 2.3.x + com.google.devtools.ksp (latest KSP2 for 2.3.x)
// AGP 9 has Kotlin built in: if the kotlin-android plugin is rejected, remove it — read the error, don't fight it.
```

Base commit checklist (**everyone branches from this, and it must already pass `./gradlew assembleDebug`**):
1. Android Studio project from the starter, package fixed in **both** `MainActivity.kt` and the FileProvider authority, minSdk 34, portrait lock.
2. **All** dependencies above, plus the FileProvider and **AppFunctions service manifest entry** (`BIND_APP_FUNCTION_SERVICE`, action `android.app.appfunctions.AppFunctionService`; copy it from the ML Kit "define tools" page).
3. One trivial `@AppFunctionDeclaration` so KSP runs.
4. The file split and stubs from §6, so every later merge compiles.
5. After this, **only A edits** `build.gradle.kts`, `AndroidManifest.xml`, `Model.kt` and `MainActivity.kt`.

⚠️ If KSP or AppFunctions won't compile within **10 minutes**, take the AppFunctions deps out of the base commit, ship without R1, and promote R1b to the main on-device path.

## 8. 60-minute timeline

| Time | A — UI + glue | B — Perception + rules | C — On-device agent | D — Cloud + demo |
|---|---|---|---|---|
| **T-10** | Base commit (§7) with C | Device gate (§3.3): accounts, intents, sample cards | Base commit with A; KSP green | Firebase project + `google-services.json` |
| T+0–10 | Ladder against stubs, trace, badge | OCR on a sample card | Smoke test: `runSingleTurnAsync("hi")`; log the tool PID vs the Activity PID | Starter cloud path green → tag `golden` |
| T+10–25 | Streaming cards, "Approve all" | `perceive()` (entities, name heuristic) + `rulePlan()` | 5 tools + agent prompt → first tool call in the trace | Demo script; collect cards |
| **T+25 cut** | | | **No R1 tool call yet → switch to R1b** | |
| T+25–45 | Force-rung chips, polish | Entity Extraction model in airplane mode; note datetime → 10:00 | Tune KDoc/prompt on 3 cards; R1b if time | FR-14 text-only cloud input |
| T+45–55 | **Feature freeze.** Full run ×3 | Airplane-mode run | Force each rung and check the fallback | **Screen-record a good 🔒 run**; rehearse |
| T+55–60 | Buffer | | | |

**Device time:** there is one Pixel 11. **C has it until T+25**, then A does integration on it. Everyone else works on an **emulator** (OCR, Entity Extraction, R2 and R3 all run there).
**Merge rule:** each person owns their files. Make small commits and `git pull --rebase` often. C keeps KSP/tool changes on a branch until they're green.
**With 3 people:** D's work goes to B (R2 is already green from the starter); C's fallback to R1b is the priority.

## 9. Demo script (2 min)

1. **Problem (15 s):** "50 cards, 90% never followed up, and they're full of personal data."
2. **Airplane mode on ✈️.** Scan a real card; note: "Met at booth, wants a demo next Tuesday."
3. **Run:** the trace streams `🔒 OCR → entities → Gemini Nano agent → saveContact → draftFollowupEmail → scheduleFollowup(Tue 10:00) → showOnMap`, and the cards appear as each tool fires.
4. **Offline taps:** contact saved → calendar invite **on Tuesday** → email draft.
5. **"Back online"**: turn airplane mode off, then tap map and LinkedIn (they need network).
6. **Close:** "One photo, no typing, and while it decided, nothing left the phone. The agent plans and calls tools; you consent to anything irreversible."

**If neither on-device rung works:** skip the airplane-mode step and demo the ☁️ cloud agent as the agent. Never claim offline while the badge shows ⚙️ Rules.

## 10. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| KSP / AppFunctions / Kotlin 2.3 build friction | **Med-High** | Pre-validated base commit at T-10; 10-minute timebox; R1b needs no KSP |
| `enableAppFunctions` doesn't discover our tools, or the service runs out-of-process | Med | PID log at T+10; fall back to `AppFunctionAgentTools.discover()` + `addTools()`; then R1b |
| Nano tool-call quality (wrong or missing args) | Med | Grounded `CardFacts`; `isValid()` filter; fill missing args from facts |
| One device shared by 4 people | High | Device slots + emulator for everything else |
| Entity Extraction model missing offline | Med | Download on Wi-Fi at T-10; airplane-mode check |
| Missing intent targets / accounts on the device | Med | Fire all 5 intents at T-10 |
| Cloud model name retired | Low-Med | Check the Firebase console at T-10 |
| Cold Nano + fallthrough stalls on stage | Med | Warm at start; offline skips R2; force-rung chips |

## 11. Open questions
- Team of 3 or 4? (See the 3-person note in §8.)
- Coding time: the deck says **2 hours**; this plan assumes 60 minutes. With 2 hours, R1b and FR-14 become P0 and FR-15 comes into scope.

## 12. Review log

| Reviewer | Key findings applied |
|---|---|
| Kotlin specialist | Atomic `ActionQueue` with `runId` + dedupe; rethrow `CancellationException`; pre-validated base commit with all deps + stubs; minSdk 34; Entity Extraction reference time, note-only datetime, DAY → 10:00; one `AgentRunner` created at start; R1 timeout 15 s; skip R2 offline; force-rung chips; portrait lock; key `done` by tool; validate args |
| Architecture review | Added the **R1b Prompt-API planner** as the agentic offline backup; R3 never pitched as the agent; R2 starter tagged `golden` as the guaranteed agentic floor; `name/company/title` in `CardFacts`; airplane-mode demo order (map/LinkedIn after reconnecting); honest privacy wording; streaming cards + "Approve all"; device slots + emulator; C's cut line moved to T+25; screen-record a good run |
