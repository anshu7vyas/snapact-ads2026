package com.example.snapact

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// ---------------------------------------------------------------- Shared contract (owner: WS1, frozen)
// Every workstream codes against these types. Don't change them without telling the team.

/** One proposed tool call. Tool names match Actions.kt: save_contact, draft_followup_email,
 *  schedule_followup, find_on_linkedin, show_on_map. */
data class Action(val tool: String, val args: Map<String, String>)

/** Everything perception (WS2) extracted from the card + note. Fed to every planner. */
data class CardFacts(
    val rawText: String,
    val name: String?, val company: String?, val title: String?,
    val emails: List<String>, val phones: List<String>,
    val addresses: List<String>, val urls: List<String>,
    val noteDateTimeMs: Long?,
)

/** Which step of the ladder produced the actions; shown as a badge in the UI. */
enum class Rung(val badge: String) {
    ON_DEVICE_AGENT("🔒 On-device agent"),
    ON_DEVICE_PLANNER("🔒 On-device planner"),
    RULES("⚙️ Rules"),
}

/** The single action sink. AppFunction tools (WS3) and the ladder (WS1) write here; the UI reads. */
object ActionQueue {
    private val _actions = MutableStateFlow<List<Action>>(emptyList())
    val actions: StateFlow<List<Action>> = _actions

    @Volatile var runId = 0L
        private set

    fun reset() {
        runId++
        _actions.value = emptyList()
    }

    /** Drops stale-run and duplicate-tool actions; one action per tool in this app. */
    fun add(a: Action, forRun: Long = runId) {
        if (forRun != runId) return
        _actions.update { list -> if (list.any { it.tool == a.tool }) list else list + a }
    }
}
