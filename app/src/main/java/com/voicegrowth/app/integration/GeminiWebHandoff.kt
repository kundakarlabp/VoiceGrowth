package com.voicegrowth.app.integration

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Explicit, manual web handoff: no undocumented Gemini REST endpoint and no
 * upload, attachment, account automation, or clinical-data transfer.
 */
object GeminiWebHandoff {
    const val GEMINI_URL = "https://gemini.google.com/app"
    const val PROMPT = """
VoiceGrowth consultation draft extraction — use only the attachment I explicitly provide.

Transcribe spoken Telugu and English faithfully if an audio file is attached.
Preserve code-switching and clinically important language. Mark inaudible words
[inaudible] and uncertain clinical terms [verify]. Never invent CR numbers,
patient names, doses, organisms, susceptibility results or patient outcomes.
Interpret 'Mero' as a possible shorthand for meropenem and 'BioFire' as a
syndromic molecular test, but retain uncertainty when audio is unclear.
'CRO' is ambiguous without clinical context; do not silently assume an organism.

For each consultation, prepare a DRAFT, not a verified patient record:
1. Recording source filename and recording date, when supplied.
2. Source timestamp or exact text span for each clinically consequential fact.
3. De-identified encounter identifier (if supplied); otherwise "unknown".
4. Department/ward, clinical context and working diagnosis.
5. Cultures/microbiology, antimicrobial agents and documented doses/dates.
6. Infectious diseases recommendations, acceptance status, plans and follow-up.
7. Explicit missing/uncertain data requiring clinician review.
8. A compact JSON object ready to review for VoiceGrowth VG Consults and
   VG Clinical Events. Use null for any unknown value; do not fabricate it.

Do not claim clinical verification. Do not write to Google Sheets or any
patient registry until a clinician confirms the draft. Use only authorized,
de-identified material; do not request personal identifiers.
""".trimIndent()

    /** Only the generic prompt is copied; the selected recording is NEVER sent. */
    fun copyInstructions(context: Context) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("VoiceGrowth Gemini instructions", PROMPT))
    }

    /** User must attach a consented, de-identified recording through Gemini's UI. */
    fun openGemini(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
