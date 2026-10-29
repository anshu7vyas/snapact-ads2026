package com.example.snapact

import android.os.Process
import android.util.Log
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint

// ---------------------------------------------------------------- Agent tools (owner: WS3)
// KSP generates SnapActAppFunctionService (registered in AndroidManifest.xml) from this class.
// Tools never act: they enqueue an Action for the user to approve. KDoc is the agent's prompt.

private const val QUEUED = "queued for user approval"

@Volatile private var pidLogged = false

private fun enqueue(tool: String, vararg args: Pair<String, String?>): String {
    if (!pidLogged) {
        pidLogged = true
        Log.i("SnapAct", "AppFunction tools running in pid ${Process.myPid()}")
    }
    val clean = args.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
    Log.i("SnapAct", "tool $tool(${clean.keys.joinToString()})")
    ActionQueue.add(Action(tool, clean))
    return QUEUED
}

@AppFunctionServiceEntryPoint(
    serviceName = "SnapActAppFunctionService",
    appFunctionXmlFileName = "snapact_app_functions",
)
abstract class SnapActTools : AppFunctionService() {

    /**
     * Saves the person from the business card to the phone's contacts. Always call this exactly once.
     *
     * @param name The person's full name, exactly as printed on the card
     * @param company The company name, if known
     * @param title The job title, if known
     * @param phone The phone number, if known
     * @param email The email address, if known
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun save_contact(
        name: String,
        company: String? = null,
        title: String? = null,
        phone: String? = null,
        email: String? = null,
    ): String = enqueue(
        "save_contact",
        "name" to name, "company" to company, "title" to title, "phone" to phone, "email" to email,
    )

    /**
     * Drafts a short, friendly follow-up email to the person. Call only if the card has an email
     * address. Personalize the body using the user's note (where you met, what you discussed).
     *
     * @param to The email address from the card
     * @param subject A short subject line, e.g. "Great meeting you"
     * @param body A 2-4 sentence email body that references the note and ends with a polite sign-off
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun draft_followup_email(to: String, subject: String, body: String): String =
        enqueue("draft_followup_email", "to" to to, "subject" to subject, "body" to body)

    /**
     * Adds a follow-up calendar event. Call once. Use the date and time from the note if it mentions
     * one, otherwise two days from now at 10:00.
     *
     * @param title Event title, e.g. "Follow up with Jane Doe (Acme)"
     * @param start Local start time formatted exactly yyyy-MM-ddTHH:mm, e.g. 2026-10-31T10:00
     * @param notes Optional context from the note
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun schedule_followup(title: String, start: String, notes: String? = null): String =
        enqueue("schedule_followup", "title" to title, "start" to start, "notes" to notes)

    /**
     * Searches LinkedIn for the person. Always call this when the person's name and company are known.
     *
     * @param query The person's name followed by the company, e.g. "Jane Doe Acme Robotics"
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun find_on_linkedin(query: String): String = enqueue("find_on_linkedin", "query" to query)

    /**
     * Shows the card's street address on a map. Call only if the card has a postal or street address.
     *
     * @param address The full street address exactly as printed on the card
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun show_on_map(address: String): String = enqueue("show_on_map", "address" to address)
}
