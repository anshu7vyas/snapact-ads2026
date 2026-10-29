package com.example.snapact

import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint

// ---------------------------------------------------------------- Agent tools (owner: WS3)
// KSP generates SnapActAppFunctionService (registered in AndroidManifest.xml) from this class.
// Tools never act: they enqueue an Action for the user to approve. KDoc is the agent's prompt.
// TODO(WS3): add draft_followup_email, schedule_followup, find_on_linkedin, show_on_map.

@AppFunctionServiceEntryPoint(
    serviceName = "SnapActAppFunctionService",
    appFunctionXmlFileName = "snapact_app_functions",
)
abstract class SnapActTools : AppFunctionService() {

    /**
     * Saves the person from the business card to the phone's contacts. Always call this once.
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
    ): String {
        val args = mapOf("name" to name, "company" to company, "title" to title, "phone" to phone, "email" to email)
        ActionQueue.add(Action("save_contact", args.filterValues { it != null }.mapValues { it.value!! }))
        return "queued for user approval"
    }
}
