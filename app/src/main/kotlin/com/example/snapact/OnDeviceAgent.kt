package com.example.snapact

import android.util.Log
import com.google.mlkit.genai.agent.Agent
import com.google.mlkit.genai.agent.AgentEvent
import com.google.mlkit.genai.agent.AgentRunner
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------- On-device agent R1 + planner R1b (owner: WS3)

private const val TAG = "SnapAct"

private val AGENT_INSTRUCTIONS = """
You are SnapAct, an on-device assistant. The user photographed a business card and wrote a short note.
Propose follow-up actions by calling tools. Tools only queue actions for the user to approve.
Rules:
- Always call save_contact once with every field you know.
- If there is an email address, call draft_followup_email with a short body personalized using the note.
- Call schedule_followup at the date/time mentioned in the note; otherwise two days from now at 10:00. Format start as yyyy-MM-ddTHH:mm.
- Call show_on_map only if there is a street address.
- Call find_on_linkedin when both name and company are known, query = "name company".
- Call all relevant tools in a single turn, then reply with one short sentence. Never invent data that is not on the card.
""".trimIndent()

/** Single lazily-created runner; the model session is expensive to spin up. */
private val runner: AgentRunner by lazy {
    val agent = Agent.builder()
        .setName("SnapAct")
        .setInstructions(AGENT_INSTRUCTIONS)
        .enableAppFunctions(true)
        .setMaxTurns(5)
        .build()
    AgentRunner(agent)
}

private fun describe(facts: CardFacts, note: String): String = buildString {
    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm (EEEE)"))
    appendLine("Now: $now")
    appendLine("Card:")
    appendLine("- name: ${facts.name ?: "unknown"}")
    appendLine("- company: ${facts.company ?: "unknown"}")
    appendLine("- title: ${facts.title ?: "unknown"}")
    appendLine("- emails: ${facts.emails.joinToString().ifEmpty { "none" }}")
    appendLine("- phones: ${facts.phones.joinToString().ifEmpty { "none" }}")
    appendLine("- addresses: ${facts.addresses.joinToString(" | ").ifEmpty { "none" }}")
    appendLine("- urls: ${facts.urls.joinToString().ifEmpty { "none" }}")
    appendLine("- raw text: ${facts.rawText.replace('\n', ' ').take(600)}")
    appendLine("Note: ${note.ifBlank { "(none)" }}")
}

/** R1: ML Kit GenAI AgentRunner with SnapActTools. Tools enqueue into ActionQueue themselves.
 *  GenAiException (NOT_AVAILABLE/BUSY/MAX_TURNS_EXCEEDED) propagates; the ladder falls through. */
suspend fun onDeviceAgentPlan(facts: CardFacts, note: String, onStep: (String) -> Unit) {
    onStep("🔒 On-device agent thinking…")
    val prompt = describe(facts, note) + "\nPropose the follow-up actions now."
    // Nano also echoes tool calls as raw text tokens (<ctrl42>call:...). Keep them and enqueue
    // at the end only if the SDK dispatched no real FunctionCall.
    var sawCall = false
    val fromText = mutableListOf<Action>()
    try {
        runner.runSingleTurnAsync(Content.builder().text(prompt).build()).collect { ev ->
            when (ev) {
                is AgentEvent.FunctionCall -> {
                    sawCall = true
                    Log.i(TAG, "agent → ${ev.name}(${ev.args})")
                    onStep("→ ${ev.name}(${argKeys(ev.args)})")
                }
                is AgentEvent.TextMessage -> if (!ev.isPartial) {
                    Log.i(TAG, "agent text: ${ev.text}")
                    val raw = parseRawToolCalls(ev.text)
                    if (raw.isEmpty()) onStep("✅ ${ev.text}") else fromText += raw
                }
                else -> Unit
            }
        }
        if (!sawCall) fromText.forEach {
            onStep("→ ${it.tool}(${it.args.keys.joinToString()}) [parsed from text]")
            ActionQueue.add(it)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "agent failed", e)
        throw e
    }
}

private val RAW_CALL = Regex("""call:(\w+)\{(.*?)\}(?=\s*(?:<ctrl43>|<ctrl42>|$))""", RegexOption.DOT_MATCHES_ALL)
private val RAW_ARG = Regex("""(\w+):<ctrl46>(.*?)<ctrl46>""", RegexOption.DOT_MATCHES_ALL)

/** Parses `<ctrl42>call:tool{key:<ctrl46>value<ctrl46>,...}<ctrl43>` sequences into Actions. */
internal fun parseRawToolCalls(text: String): List<Action> = RAW_CALL.findAll(text).map { m ->
    val args = RAW_ARG.findAll(m.groupValues[2])
        .map { it.groupValues[1] to it.groupValues[2].trim() }
        .filter { it.second.isNotBlank() }
        .toMap()
    Action(m.groupValues[1], args)
}.toList()

private fun argKeys(args: Any?): String = when (args) {
    is Map<*, *> -> args.keys.joinToString()
    else -> runCatching { Json.parseToJsonElement(args.toString()).jsonObject.keys.joinToString() }
        .getOrDefault(args.toString().take(60))
}

private val PLANNER_SPEC = """
Return ONLY a JSON array, no prose, no markdown. Each element: {"tool":"<name>","args":{...}} with string values.
Tools:
- save_contact(name, company?, title?, phone?, email?) — always include once.
- draft_followup_email(to, subject, body) — only if there is an email; body 2-4 sentences personalized with the note.
- schedule_followup(title, start, notes?) — start as yyyy-MM-ddTHH:mm; the note's date/time, else two days from now at 10:00.
- find_on_linkedin(query) — only if name and company are known; query = "name company".
- show_on_map(address) — only if there is a street address.
Never invent data that is not on the card.
""".trimIndent()

/** R1b: Prompt API returns JSON [{tool,args}] over the same 5 tools. */
suspend fun onDevicePlannerPlan(facts: CardFacts, note: String, onStep: (String) -> Unit): List<Action> {
    onStep("🔒 On-device planner (Prompt API)…")
    val prompt = PLANNER_SPEC + "\n\n" + describe(facts, note)
    val response = Generation.getClient()
        .generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0.2f })
    val text = response.candidates.firstOrNull()?.text.orEmpty()
    Log.i(TAG, "planner raw: $text")
    val actions = parseActions(text)
    if (actions.isEmpty()) onStep("⚠️ Planner returned no parseable actions")
    else actions.forEach { onStep("→ ${it.tool}(${it.args.keys.joinToString()})") }
    return actions
}

/** Strips ``` fences / stray prose and parses [{tool,args}]; empty on any failure. */
internal fun parseActions(raw: String): List<Action> = runCatching {
    val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = body.indexOf('[')
    val end = body.lastIndexOf(']')
    if (start < 0 || end <= start) return emptyList()
    Json.parseToJsonElement(body.substring(start, end + 1)).jsonArray.mapNotNull { el ->
        val o = el.jsonObject
        val tool = o["tool"]?.jsonPrimitive?.content ?: return@mapNotNull null
        val args = (o["args"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }?.let { k to it }
        }.toMap()
        Action(tool, args)
    }
}.getOrDefault(emptyList())
