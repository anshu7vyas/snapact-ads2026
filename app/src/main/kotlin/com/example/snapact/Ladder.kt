package com.example.snapact

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

// ---------------------------------------------------------------- Ladder (owner: WS1)
// Perceive once, then try each planner top-down; stop at the first that yields a valid action.

/** Demo build: only the on-device agent runs and the debug chips are hidden.
 *  Flip to true to re-enable the Prompt API planner (R1b), rules (R3) and force-step chips. */
const val SHOW_BACKUPS = false

suspend fun runLadder(card: Bitmap, note: String, forced: Rung?, onStep: (String) -> Unit): Rung? {
    val facts = perceive(card, note, onStep)
    if (forced != null) onStep("⚙️ Forced ${forced.badge}")

    suspend fun attempt(rung: Rung, ms: Long, block: suspend () -> List<Action>?): Boolean {
        if (forced != null && forced != rung) return false
        ActionQueue.reset()
        val run = ActionQueue.runId
        try {
            withTimeoutOrNull(ms) { block() }?.filter(::isValid)?.forEach { ActionQueue.add(it, run) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onStep("⚠️ ${rung.badge} failed: ${e.message}")
        }
        val n = ActionQueue.actions.value.size
        if (n > 0) {
            onStep("${rung.badge} proposed ${actionCount(n)}")
        } else if (forced == rung) {
            onStep("⚠️ Forced ${rung.badge} produced nothing")
        }
        return n > 0
    }

    // R1: tools enqueue themselves, so the block returns null.
    if (attempt(Rung.ON_DEVICE_AGENT, 15_000) { onDeviceAgentPlan(facts, note, onStep); null }) {
        return Rung.ON_DEVICE_AGENT
    }
    if (!SHOW_BACKUPS) {
        onStep("⚠️ On-device agent couldn't finish. Tap Run agent to try again.")
        return null
    }
    if (attempt(Rung.ON_DEVICE_PLANNER, 10_000) { onDevicePlannerPlan(facts, note, onStep) }) {
        return Rung.ON_DEVICE_PLANNER
    }
    // A forced model rung that yields nothing still falls through to rules; the trace says so above.
    onStep(if (forced == Rung.RULES) "⚙️ Running rules" else "⚙️ Falling back to rules")
    ActionQueue.reset()
    rulePlan(facts).filter(::isValid).forEach { ActionQueue.add(it) }
    onStep("${Rung.RULES.badge} proposed ${actionCount(ActionQueue.actions.value.size)}")
    return Rung.RULES
}

fun isValid(a: Action): Boolean = when (a.tool) {
    "save_contact" -> !a.args["name"].isNullOrBlank()
    "draft_followup_email" -> a.args["to"]?.contains('@') == true
    "schedule_followup" -> parsesAsDateTime(a.args["start"])
    "find_on_linkedin" -> !a.args["query"].isNullOrBlank()
    "show_on_map" -> !a.args["address"].isNullOrBlank()
    else -> false
}

private fun actionCount(n: Int) = "$n action${if (n == 1) "" else "s"}"

/** Local date-time to the minute ("2026-10-29T14:00" or "2026-10-29 14:00"); trailing seconds/offset ignored. */
private fun parsesAsDateTime(s: String?): Boolean =
    s != null && runCatching { LocalDateTime.parse(s.trim().replace(' ', 'T').take(16)) }.isSuccess
