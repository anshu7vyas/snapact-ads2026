# SnapAct — on-device card agent (ADS 2026 hackathon)

Photograph a business card and add a quick note, and SnapAct's agent proposes the follow-up actions: save the contact, draft a follow-up email, create a calendar invite, open the address in Maps, or look the person up on LinkedIn. A human approves every action before it runs. OCR and entity extraction run on-device with ML Kit; Gemini (via Firebase AI Logic) decides which tools to call.

- **Setup:** see [docs/SETUP.md](docs/SETUP.md)
- **Plan:** see docs/PRD.md

> **Note:** each teammate must add their own `app/google-services.json` from the Firebase console. It is gitignored and must never be committed.
