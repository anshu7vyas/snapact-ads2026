package com.example.snapact

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// ---------------------------------------------------------------- Ladder (owner: WS1)
// Perceive once, then try each planner top-down; stop at the first that yields a valid action.

suspend fun runLadder(card: Bitmap, note: String, forced: Rung?, onStep: (String) -> Unit): Rung {
    val facts = perceive(card, note, onStep)

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
        return ActionQueue.actions.value.isNotEmpty()
    }

    // R1: tools enqueue themselves, so the block returns null.
    if (attempt(Rung.ON_DEVICE_AGENT, 15_000) { onDeviceAgentPlan(facts, note, onStep); null }) {
        return Rung.ON_DEVICE_AGENT
    }
    if (attempt(Rung.ON_DEVICE_PLANNER, 10_000) { onDevicePlannerPlan(facts, note, onStep) }) {
        return Rung.ON_DEVICE_PLANNER
    }
    onStep("⚙️ Falling back to rules")
    ActionQueue.reset()
    rulePlan(facts).filter(::isValid).forEach { ActionQueue.add(it) }
    return Rung.RULES
}

// TODO(WS1): tighten — e.g. parseable `start` for schedule_followup.
fun isValid(a: Action): Boolean = when (a.tool) {
    "save_contact" -> !a.args["name"].isNullOrBlank()
    "draft_followup_email" -> !a.args["to"].isNullOrBlank()
    "schedule_followup" -> !a.args["start"].isNullOrBlank()
    "find_on_linkedin" -> !a.args["query"].isNullOrBlank()
    "show_on_map" -> !a.args["address"].isNullOrBlank()
    else -> false
}
