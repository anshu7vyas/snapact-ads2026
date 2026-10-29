package com.example.snapact

import android.graphics.Bitmap

// ---------------------------------------------------------------- Perception (owner: WS2)
// STUB — replace with ML Kit Text Recognition v2 + Entity Extraction + name heuristic (PRD §8, PR #2).

suspend fun perceive(card: Bitmap, note: String, onStep: (String) -> Unit): CardFacts {
    onStep("🔒 (stub) perception")
    return CardFacts(
        rawText = "", name = null, company = null, title = null,
        emails = emptyList(), phones = emptyList(), addresses = emptyList(), urls = emptyList(),
        noteDateTimeMs = null,
    )
}
