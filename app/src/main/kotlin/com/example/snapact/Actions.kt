package com.example.snapact

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import java.time.LocalDateTime
import java.time.ZoneId

// ---------------------------------------------------------------- Tools -> Android apps (owner: WS1)

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
