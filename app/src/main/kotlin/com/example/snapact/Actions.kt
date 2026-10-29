package com.example.snapact

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

// ---------------------------------------------------------------- Tools -> Android apps (owner: WS1)

/** Writes need a tap of consent; these are pre-checked. Map/LinkedIn are read-only links. */
val DEFAULT_SELECTED = setOf("save_contact", "schedule_followup", "draft_followup_email")

/** Lets contact + calendar be written directly instead of opening their apps. */
val WRITE_PERMISSIONS = arrayOf(
    Manifest.permission.READ_CONTACTS, // only for the "View" link after saving
    Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.READ_CALENDAR,
    Manifest.permission.WRITE_CALENDAR,
)

/** Result of an approved action; `view` reopens what was written in its app. */
data class Outcome(val message: String, val view: Intent? = null)

/**
 * Carries out an approved action without leaving SnapAct where possible.
 * Returns null when the action needs its own app (email, map, LinkedIn, or a write that
 * isn't permitted / failed). The caller then opens it with [intentFor].
 */
fun approveInPlace(ctx: Context, a: Action): Outcome? = runCatching {
    when (a.tool) {
        "save_contact" ->
            if (granted(ctx, Manifest.permission.WRITE_CONTACTS)) writeContact(ctx, a.args) else null
        "schedule_followup" ->
            if (granted(ctx, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
                writeEvent(ctx, a.args)
            } else null
        else -> null
    }
}.getOrNull()

/** Opens several apps in one go; Android stacks them and the user returns through each. */
fun openAll(ctx: Context, actions: List<Action>) {
    val intents = actions.mapNotNull(::intentFor)
    if (intents.isEmpty()) return
    try {
        ctx.startActivities(intents.toTypedArray())
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found for one of the actions", Toast.LENGTH_SHORT).show()
    }
}

fun execute(ctx: Context, a: Action) {
    val intent = intentFor(a) ?: return
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found for ${a.tool}", Toast.LENGTH_SHORT).show()
    }
}

fun intentFor(a: Action): Intent? {
    val g = a.args
    return when (a.tool) {
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
            val ms = startMillis(g)
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
        else -> null
    }
}

fun label(tool: String) = when (tool) {
    "save_contact" -> "👤 Save contact"
    "draft_followup_email" -> "✉️ Draft follow-up email"
    "schedule_followup" -> "📅 Schedule follow-up"
    "find_on_linkedin" -> "🔎 Find on LinkedIn"
    "show_on_map" -> "📍 Show on map"
    else -> tool
}

/** Software bitmap (required by ML Kit), longest edge ≤ ~1600 px. ImageDecoder applies EXIF rotation. */
fun loadBitmap(ctx: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 1600) d.setTargetSampleSize(longest / 1600 + 1)
    }

// ---------------------------------------------------------------- Direct writes

private fun granted(ctx: Context, vararg perms: String) =
    perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** `start` is "yyyy-MM-ddTHH:mm"; falls back to 2 days out at 10:00. */
private fun startMillis(g: Map<String, String>): Long {
    val start = runCatching { LocalDateTime.parse(g["start"]!!.take(16)) }
        .getOrElse { LocalDateTime.now().plusDays(2).withHour(10).withMinute(0) }
    return start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** Inserts a device-local contact. */
private fun writeContact(ctx: Context, g: Map<String, String>): Outcome {
    val data = ContactsContract.Data.CONTENT_URI
    val ops = arrayListOf(
        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
    )
    fun row(mime: String, vararg kv: Pair<String, String?>) {
        if (kv.all { it.second.isNullOrBlank() }) return
        ops += ContentProviderOperation.newInsert(data)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mime)
            .apply { kv.forEach { (k, v) -> withValue(k, v) } }
            .build()
    }
    row(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to g["name"])
    row(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Organization.COMPANY to g["company"],
        ContactsContract.CommonDataKinds.Organization.TITLE to g["title"])
    row(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.NUMBER to g["phone"],
        ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_WORK.toString())
    row(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.ADDRESS to g["email"],
        ContactsContract.CommonDataKinds.Email.TYPE to ContactsContract.CommonDataKinds.Email.TYPE_WORK.toString())

    val rawUri = ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!
    // Saved from here on: a failed lookup must not make the caller fall back and save twice.
    val lookup = runCatching {
        ContactsContract.RawContacts.getContactLookupUri(ctx.contentResolver, rawUri)
    }.getOrNull()
    return Outcome("✓ Saved to contacts", lookup?.let { Intent(Intent.ACTION_VIEW, it) })
}

/** Inserts a 30-min event into the primary writable calendar. */
private fun writeEvent(ctx: Context, g: Map<String, String>): Outcome? {
    val calendarId = ctx.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
        null,
        "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null // no calendar → open the app

    val start = startMillis(g)
    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, g["title"])
        put(CalendarContract.Events.DESCRIPTION, g["notes"])
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, start + 30 * 60_000)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }
    val eventUri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
    val id = ContentUris.parseId(eventUri)
    return Outcome(
        "✓ Added to calendar",
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)),
    )
}
