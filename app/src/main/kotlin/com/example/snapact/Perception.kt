package com.example.snapact

import android.graphics.Bitmap
import com.google.mlkit.nl.entityextraction.DateTimeEntity
import com.google.mlkit.nl.entityextraction.Entity
import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractor
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

// ---------------------------------------------------------------- Perception (owner: WS2)
// ML Kit Text Recognition v2 (OCR) + Entity Extraction + a name/company/title heuristic. All on-device.

private val extractor: EntityExtractor by lazy {
    EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
}

private val EMAIL_RE = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
private val PHONE_RE = Regex("""\+?\d[\d ().-]{7,}\d""")
private val TITLE_RE = Regex(
    """\b(Engineer|Manager|Director|Founder|CEO|CTO|VP|Lead|Head|Designer|Developer|President|Officer)\b""",
    RegexOption.IGNORE_CASE,
)
private val COMPANY_SUFFIX_RE = Regex("""\b(Inc|LLC|Corp|Labs|Ltd|GmbH|Co|Robotics)\b\.?""", RegexOption.IGNORE_CASE)
private val NAME_RE = Regex("""^\p{Lu}[\p{L}'.-]*(\s+\p{Lu}[\p{L}'.-]*){1,2}$""")

suspend fun perceive(card: Bitmap, note: String, onStep: (String) -> Unit): CardFacts {
    val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        .process(InputImage.fromBitmap(card, 0)).await().text
    onStep("🔒 OCR: ${text.length} chars")

    val emails = mutableListOf<String>()
    val phones = mutableListOf<String>()
    val addresses = mutableListOf<String>()
    val urls = mutableListOf<String>()
    var noteDateTimeMs: Long? = null

    val modelReady = try {
        val ok = withTimeoutOrNull(3_000) { extractor.downloadModelIfNeeded().await() } != null
        if (!ok) onStep("⚠️ Entity model download timed out (offline?); using regex fallbacks")
        ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onStep("⚠️ Entity model unavailable (${e.message}); using regex fallbacks")
        false
    }

    if (modelReady) {
        try {
            for (a in extractor.annotate(text).await()) {
                for (entity in a.entities) {
                    when (entity.type) {
                        Entity.TYPE_EMAIL -> emails += a.annotatedText
                        Entity.TYPE_PHONE -> phones += a.annotatedText
                        Entity.TYPE_ADDRESS -> addresses += a.annotatedText
                        Entity.TYPE_URL -> urls += a.annotatedText
                    }
                }
            }
            if (note.isNotBlank()) noteDateTimeMs = extractNoteDate(note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onStep("⚠️ Entity extraction failed (${e.message}); using regex fallbacks")
        }
    }

    if (emails.isEmpty()) emails += EMAIL_RE.findAll(text).map { it.value }
    if (phones.isEmpty()) phones += PHONE_RE.findAll(text).map { it.value.trim() }
    // ML Kit sometimes tags the email domain as a URL; drop those.
    urls.removeAll { u -> emails.any { it.contains(u, ignoreCase = true) } }

    val stem = emails.firstOrNull()?.substringAfter('@')?.substringBefore('.')?.takeIf { it.length >= 2 }
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val contactBits = (emails + phones + addresses + urls)
    fun isContactLine(l: String) = '@' in l || l.count { it.isDigit() } >= 5 ||
        contactBits.any { it.contains(l, true) || l.contains(it, true) }

    val name = lines.firstOrNull {
        NAME_RE.matches(it) && it.none(Char::isDigit) && '@' !in it && !TITLE_RE.containsMatchIn(it) &&
            !COMPANY_SUFFIX_RE.containsMatchIn(it) && (stem == null || !it.replace(" ", "").contains(stem, true))
    }
    val title = lines.firstOrNull { it != name && TITLE_RE.containsMatchIn(it) && !isContactLine(it) }
    val company = stem?.let { s -> lines.firstOrNull { it != name && it != title && !isContactLine(it) && it.contains(s, true) } }
        ?: lines.firstOrNull { it != name && it != title && !isContactLine(it) }

    onStep(
        "🔒 Entities: name=${name ?: "?"}, company=${company ?: "?"}, title=${title ?: "?"}, " +
            "${emails.size} email, ${phones.size} phone, ${addresses.size} address, ${urls.size} url"
    )
    onStep(
        "🔒 Note date: " + (noteDateTimeMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString() }
            ?: if (modelReady) "none" else "none (model unavailable)")
    )

    return CardFacts(
        rawText = text, name = name, company = company, title = title,
        emails = emails.distinct(), phones = phones.distinct(),
        addresses = addresses.distinct(), urls = urls.distinct(),
        noteDateTimeMs = noteDateTimeMs,
    )
}

/** First date/time in the note; day-or-coarser granularity is pinned to 10:00 local. */
private suspend fun extractNoteDate(note: String): Long? {
    val params = EntityExtractionParams.Builder(note)
        .setEntityTypesFilter(setOf(Entity.TYPE_DATE_TIME))
        .setReferenceTime(System.currentTimeMillis())
        .setReferenceTimeZone(TimeZone.getDefault())
        .build()
    val dt = extractor.annotate(params).await()
        .flatMap(EntityAnnotation::getEntities)
        .firstOrNull { it.type == Entity.TYPE_DATE_TIME } as? DateTimeEntity ?: return null
    val ms = dt.timestampMillis
    return if (dt.dateTimeGranularity <= DateTimeEntity.GRANULARITY_DAY) { // YEAR=0 … DAY=3: pin to 10:00
        val zone = ZoneId.systemDefault()
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    } else ms
}
