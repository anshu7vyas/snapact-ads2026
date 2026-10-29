package com.example.snapact // <-- change to your package

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

// ---------------------------------------------------------------- Agent

data class Action(val tool: String, val args: Map<String, String>)

private val toolDecls = listOf(
    FunctionDeclaration(
        "save_contact", "Save the person to the phone's contacts",
        mapOf(
            "name" to Schema.string("Full name"),
            "company" to Schema.string("Company"),
            "title" to Schema.string("Job title"),
            "phone" to Schema.string("Phone number"),
            "email" to Schema.string("Email address"),
        ),
        optionalParameters = listOf("company", "title", "phone", "email"),
    ),
    FunctionDeclaration(
        "draft_followup_email", "Draft a personalized follow-up email to the person",
        mapOf(
            "to" to Schema.string("Email address"),
            "subject" to Schema.string("Subject line"),
            "body" to Schema.string("Warm, concise body that references the user's meeting note"),
        ),
    ),
    FunctionDeclaration(
        "schedule_followup", "Add a follow-up meeting or reminder to the calendar",
        mapOf(
            "title" to Schema.string("Event title"),
            "start" to Schema.string("Local datetime, format yyyy-MM-ddTHH:mm"),
            "notes" to Schema.string("Context for the follow-up"),
        ),
        optionalParameters = listOf("notes"),
    ),
    FunctionDeclaration(
        "find_on_linkedin", "Look the person up on LinkedIn",
        mapOf("query" to Schema.string("Person name and company")),
    ),
    FunctionDeclaration(
        "show_on_map", "Show the company's address on a map",
        mapOf("address" to Schema.string("Full street address")),
    ),
)

private val model by lazy {
    Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
        modelName = "gemini-2.5-flash", // swap for the latest Flash model you have
        tools = listOf(Tool.functionDeclarations(toolDecls)),
        systemInstruction = content {
            text(
                """
                You are a networking assistant. You get a photo of a business card and the
                user's note about where/why they met. Decide which tools help and call them.
                - Always call save_contact.
                - If there is an email, call draft_followup_email, personalized with the note.
                - If the note implies timing, call schedule_followup at that time; otherwise
                  schedule one 2 business days from now at 10:00.
                - Call show_on_map only if the card has an address.
                - Call find_on_linkedin when name and company are known.
                Call all relevant tools in one turn. Then reply with a one-line summary.
                """.trimIndent()
            )
        },
    )
}

suspend fun runAgent(card: Bitmap, note: String, onStep: (String) -> Unit): List<Action> {
    val actions = mutableListOf<Action>()
    val chat = model.startChat()
    onStep("📷 Reading card with Gemini…")
    var resp = chat.sendMessage(content {
        image(card)
        text("Now: ${LocalDateTime.now()}\nMy note: ${note.ifBlank { "(none)" }}")
    })
    repeat(5) {
        val calls = resp.functionCalls
        if (calls.isEmpty()) {
            onStep("✅ ${resp.text?.trim().orEmpty()}")
            return actions
        }
        val results = calls.map { call ->
            val args = call.args.mapValues { it.value.jsonPrimitive.content }
            actions += Action(call.name, args)
            onStep("→ ${call.name}(${args.keys.joinToString()})")
            FunctionResponsePart(
                call.name,
                JsonObject(mapOf("status" to JsonPrimitive("queued for user approval"))),
            )
        }
        resp = chat.sendMessage(content("function") { results.forEach { part(it) } })
    }
    return actions
}

// ---------------------------------------------------------------- Tools -> Android apps

fun execute(ctx: Context, a: Action) {
    val g = a.args
    val intent = when (a.tool) {
        "save_contact" -> Intent(ContactsContract.Intents.Insert.ACTION)
            .setType(ContactsContract.RawContacts.CONTENT_TYPE)
            .putExtra(ContactsContract.Intents.Insert.NAME, g["name"])
            .putExtra(ContactsContract.Intents.Insert.COMPANY, g["company"])
            .putExtra(ContactsContract.Intents.Insert.JOB_TITLE, g["title"])
            .putExtra(ContactsContract.Intents.Insert.PHONE, g["phone"])
            .putExtra(ContactsContract.Intents.Insert.EMAIL, g["email"])

        "draft_followup_email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(g["to"]))
            .putExtra(Intent.EXTRA_SUBJECT, g["subject"])
            .putExtra(Intent.EXTRA_TEXT, g["body"])

        "schedule_followup" -> {
            val start = runCatching {
                LocalDateTime.parse(g["start"]!!.take(16))
            }.getOrElse { LocalDateTime.now().plusDays(2).withHour(10).withMinute(0) }
            val ms = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, g["title"])
                .putExtra(CalendarContract.Events.DESCRIPTION, g["notes"])
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 30 * 60_000)
        }

        "find_on_linkedin" -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.linkedin.com/search/results/people/?keywords=" + Uri.encode(g["query"]))
        )

        "show_on_map" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(g["address"])))
        else -> return
    }
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found for ${a.tool}", Toast.LENGTH_SHORT).show()
    }
}

private fun label(tool: String) = when (tool) {
    "save_contact" -> "👤 Save contact"
    "draft_followup_email" -> "✉️ Draft follow-up email"
    "schedule_followup" -> "📅 Schedule follow-up"
    "find_on_linkedin" -> "🔎 Find on LinkedIn"
    "show_on_map" -> "📍 Show on map"
    else -> tool
}

private fun loadBitmap(ctx: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 1600) d.setTargetSampleSize(longest / 1600 + 1)
    }

// ---------------------------------------------------------------- UI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val trace = remember { mutableStateListOf<String>() }
    val actions = remember { mutableStateListOf<Action>() }
    val done = remember { mutableStateListOf<Int>() }

    val camUri = remember {
        val f = File(ctx.cacheDir, "card.jpg").apply { createNewFile() }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fp", f)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photo = loadBitmap(ctx, camUri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        u?.let { photo = loadBitmap(ctx, it) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("SnapAct · Card Agent") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { camera.launch(camUri) }) { Text("📷 Scan card") }
                    OutlinedButton(onClick = {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Gallery") }
                }
            }
            photo?.let { bmp ->
                item {
                    Image(bmp.asImageBitmap(), "card", Modifier.fillMaxWidth().heightIn(max = 220.dp))
                }
            }
            item {
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Where/why did you meet? (optional)") },
                    placeholder = { Text("Met at booth 12, wants a demo next Tuesday") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    enabled = photo != null && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busy = true; trace.clear(); actions.clear(); done.clear()
                            try {
                                actions += runAgent(photo!!, note) { trace += it }
                            } catch (e: Exception) {
                                trace += "⚠️ ${e.message}"
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Agent working…" else "🤖 Run agent") }
            }
            if (trace.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Agent trace", fontWeight = FontWeight.Bold)
                            trace.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            }
            if (actions.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Proposed actions", fontWeight = FontWeight.Bold)
                    }
                }
            }
            itemsIndexed(actions) { i, a ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(label(a.tool), fontWeight = FontWeight.Bold)
                        a.args.forEach { (k, v) -> Text("$k: $v", maxLines = 4) }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            enabled = i !in done,
                            onClick = { execute(ctx, a); done += i },
                        ) { Text(if (i in done) "Done ✓" else "Do it") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
