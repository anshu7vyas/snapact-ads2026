package com.example.snapact

// ---------------------------------------------------------------- Rule planner R3 (owner: WS2)
// STUB — deterministic safety net, never pitched as the agent (PRD §8, PR #2).

fun rulePlan(facts: CardFacts): List<Action> =
    listOf(Action("save_contact", mapOf("name" to (facts.name ?: "Stub Contact"))))
