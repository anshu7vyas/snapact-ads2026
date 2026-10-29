package com.example.snapact

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.snapact.ui.theme.SnapActTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------- UI (owner: WS1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnapActTheme { App() } }
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
    var rung by remember { mutableStateOf<Rung?>(null) }
    val trace = remember { mutableStateListOf<String>() }
    val actions by ActionQueue.actions.collectAsState()
    // Both keyed by tool, not index. Unset selection falls back to DEFAULT_SELECTED.
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val outcomes = remember { mutableStateMapOf<String, Outcome>() }
    fun isChecked(a: Action) = selected[a.tool] ?: (a.tool in DEFAULT_SELECTED)
    val pending = actions.filter { isChecked(it) && it.tool !in outcomes }

    // Writes land in place; whatever needs its own app opens afterwards, stacked.
    fun approveSelected() {
        val external = pending.filter { a ->
            val done = approveInPlace(ctx, a)
            outcomes[a.tool] = done ?: Outcome("↗ Opened")
            done == null
        }
        openAll(ctx, external)
    }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { approveSelected() } // denied → those actions fall back to opening their app

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

    Scaffold(topBar = { TopAppBar(title = { Text("SnapAct · On-device Card Agent") }) }) { pad ->
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
                            busy = true; rung = null; trace.clear(); selected.clear(); outcomes.clear(); ActionQueue.reset()
                            try {
                                rung = runLadder(photo!!, note, forced = null) { trace += it }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                trace += "⚠️ ${e.message}"
                            } finally {
                                busy = false
                            }
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
                        rung?.let { AssistChip(onClick = {}, label = { Text(it.badge) }) }
                    }
                }
                item {
                    Button(
                        enabled = pending.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val missing = WRITE_PERMISSIONS.any {
                                ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing) permissions.launch(WRITE_PERMISSIONS) else approveSelected()
                        },
                    ) { Text("✅ Approve selected (${pending.size})") }
                }
            }
            items(actions, key = { it.tool }) { a ->
                val outcome = outcomes[a.tool]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Checkbox(
                            checked = isChecked(a),
                            enabled = outcome == null,
                            onCheckedChange = { selected[a.tool] = it },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(label(a.tool), fontWeight = FontWeight.Bold)
                            a.args.forEach { (k, v) -> Text("$k: $v", maxLines = 4) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                outcome?.let { Text(it.message, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.weight(1f))
                                // After a direct write: jump to the result. Before: open the app instead.
                                val view = outcome?.view
                                if (view != null) {
                                    TextButton(onClick = { ctx.startActivity(view) }) { Text("View") }
                                } else if (outcome == null) {
                                    TextButton(onClick = { execute(ctx, a); outcomes[a.tool] = Outcome("↗ Opened") }) {
                                        Text("Open")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
