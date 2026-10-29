package com.example.snapact

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- Rule planner R3 (owner: WS2)
// Deterministic safety net, never pitched as the agent. Arg keys match Actions.kt intentFor.

private val START_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

fun rulePlan(facts: CardFacts): List<Action> {
    val name = facts.name ?: "Unknown"
    val email = facts.emails.firstOrNull()
    val actions = mutableListOf<Action>()

    actions += Action("save_contact", buildMap {
        put("name", name)
        facts.company?.let { put("company", it) }
        facts.title?.let { put("title", it) }
        facts.phones.firstOrNull()?.let { put("phone", it) }
        email?.let { put("email", it) }
    })

    if (email != null) {
        val first = facts.name?.substringBefore(' ') ?: "there"
        val at = facts.company?.let { " and learning about $it" } ?: ""
        actions += Action("draft_followup_email", mapOf(
            "to" to email,
            "subject" to "Great meeting you",
            "body" to "Hi $first,\n\nGreat meeting you$at. I'd love to stay in touch and continue our conversation.\n\nBest,",
        ))
    }

    facts.addresses.firstOrNull()?.let { actions += Action("show_on_map", mapOf("address" to it)) }

    if (facts.name != null && facts.company != null) {
        actions += Action("find_on_linkedin", mapOf("query" to "${facts.name} ${facts.company}"))
    }

    val start = facts.noteDateTimeMs
        ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
        ?: LocalDate.now().plusDays(2).atTime(10, 0)
    actions += Action("schedule_followup", mapOf(
        "title" to "Follow up with $name",
        "start" to start.format(START_FMT),
        "notes" to listOfNotNull(facts.company, facts.title, email, facts.phones.firstOrNull()).joinToString(" · "),
    ))

    return actions
}
