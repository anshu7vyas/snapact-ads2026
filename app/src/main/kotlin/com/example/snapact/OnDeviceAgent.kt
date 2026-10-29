package com.example.snapact

// ---------------------------------------------------------------- On-device agent R1 + planner R1b (owner: WS3)
// STUBS — see PRD §8, PR #3.

/** R1: ML Kit GenAI AgentRunner with SnapActTools. Tools enqueue into ActionQueue themselves. */
suspend fun onDeviceAgentPlan(facts: CardFacts, note: String, onStep: (String) -> Unit) {
    onStep("🔒 (stub) on-device agent unavailable")
}

/** R1b: Prompt API returns JSON [{tool,args}] over the same 5 tools. */
suspend fun onDevicePlannerPlan(facts: CardFacts, note: String, onStep: (String) -> Unit): List<Action> {
    onStep("🔒 (stub) on-device planner unavailable")
    return emptyList()
}
