# SnapAct — on-device card agent

> 🏆 **1st place, Android Dev Summit 2026 hackathon**, Track 2: in-app agents with ML Kit GenAI

Photograph a business card and add a quick note ("met at the booth, wants a demo next Tuesday"). SnapAct's **on-device agent**, Gemini Nano running in AICore, reads the card and **decides which of the app's tools to call**:

- 👤 save the contact
- ✉️ draft a personalized follow-up email
- 📅 schedule the follow-up (next Tuesday, 10:00)
- 🔎 find the person on LinkedIn
- 📍 show the company on a map

You check the actions you want and tap **Approve** once. The contact and calendar event are written straight to the phone; the email opens as a Gmail draft for you to send. **The card and your note never leave the device, so the whole flow works in airplane mode.**

## How it works

```
 photo + note
     │
     ▼
 PERCEIVE (on-device)   ML Kit Text Recognition v2 → card text
                        ML Kit Entity Extraction → email, phone, address, "next Tuesday" → timestamp
                        name / company / title heuristic
     │  CardFacts
     ▼
 DECIDE (on-device)     ML Kit GenAI AgentRunner (Gemini Nano 4 via AICore)
                        tools = 5 @AppFunction functions (their KDoc is the agent's tool description)
     │  each tool call → ActionQueue (proposed, not executed)
     ▼
 APPROVE (one tap)      checkboxes → contact + calendar written directly · email → Gmail draft
```

The Track 2 stack end to end: **AppFunctions** (tools) → **Agent** → **AgentRunner** → **AgentEvent** (streamed into a live trace). Tools only enqueue actions. Nothing irreversible happens without the user's consent.

Two fallbacks also exist in code but are switched off in the demo build: a Prompt API JSON planner and a rule-based planner. Flip `SHOW_BACKUPS` in `Ladder.kt` to enable them, plus debug chips that force a specific planner.

## Build & run

- **Device:** a phone with Gemini Nano via AICore. We used a Pixel 11 Pro (Android 17) enrolled in the **AICore Developer Preview** ("Gemini Nano 4 Fast"), which the agent API requires.
- **Toolchain:** AGP 9.1.1 · Gradle 9.3.1 · Kotlin 2.3.20 · KSP 2.3.12 · compileSdk 37 · minSdk 34
- **Libraries:** `genai-agent`, `genai-prompt`, `text-recognition`, `entity-extraction` (ML Kit) · `androidx.appfunctions` + KSP

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# check that the agent's tools are registered:
adb shell cmd app_function list-app-functions --package com.example.snapact
```

Launch the app once on Wi-Fi so the small Entity Extraction model downloads. After that, everything runs offline. No Firebase or API keys are needed.

## Build Workflow

Three teammates worked in parallel, each owning separate files against a shared contract (`Model.kt`). Each workstream was reviewed before merge. The plan, requirements and work breakdown are in [docs/PRD.md](docs/PRD.md).

| Workstream | Files |
|---|---|
| App, UI and approval flow | `MainActivity.kt`, `Actions.kt`, `Ladder.kt` |
| On-device perception | `Perception.kt`, `RulePlanner.kt` |
| On-device agent and tools | `SnapActTools.kt`, `OnDeviceAgent.kt` |

## What's next

- A friendlier results screen: a one-line live status, with the full agent trace in a "How it decided" sheet
- A cloud fallback (Firebase AI Logic hybrid inference) for hard-to-read cards
