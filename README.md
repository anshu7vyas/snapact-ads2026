# SnapAct — on-device card agent (ADS 2026 hackathon, Track 2)

Photograph a business card and add a quick note. SnapAct's **on-device agent** (Gemini Nano via ML Kit GenAI `AgentRunner` + AppFunctions) decides which follow-up actions to propose: save the contact, draft a follow-up email, create a calendar invite, open the address in Maps, or look the person up on LinkedIn. A human approves every action before it runs. OCR and entity extraction run on-device with ML Kit. **Nothing leaves the phone, so it works in airplane mode.**

- **Plan & work breakdown (3 parallel PRs):** [docs/PRD.md](docs/PRD.md) — start with §8
- **Setup notes:** [docs/SETUP.md](docs/SETUP.md) (Firebase steps are no longer needed)

> No Firebase / `google-services.json` is needed (the cloud path was cut). It stays gitignored anyway; never commit secrets, because this repo is public.
