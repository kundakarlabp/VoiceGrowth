package com.voicegrowth.app.engine.ai

import com.voicegrowth.app.data.model.RecordingSource

object AiPromptBuilder {
    const val SYSTEM_INSTRUCTION = """
You are VoiceGrowth's private on-device note processor. Work only from the supplied de-identified transcript text.
Never invent or infer patient facts, diagnoses, drug doses, laboratory values, decisions, names, dates, assignments, or recommendations that were not actually stated. Preserve uncertainty. If speech is unclear, say that it is unclear rather than guessing.
Do not provide new medical advice. Capture and organize what was discussed.
Return concise Markdown only. Never include a preamble about being an AI.
"""

    fun evidencePrompt(source: RecordingSource, chunk: String, chunkIndex: Int, chunkCount: Int): String = """
This is transcript segment ${chunkIndex + 1} of $chunkCount from a ${sourceLabel(source)}.
Extract evidence-grounded notes from this segment for later synthesis.

Use these headings only when supported:
## Main points
## Decisions stated
## Action items stated
## Questions / uncertainties
## Learning or research points

Rules:
- Keep all clinically meaningful numbers exactly as spoken.
- Do not turn possibilities into diagnoses or plans.
- Do not add recommendations of your own.
- If an item is not present, omit that heading.
- Maximum 250 words.

TRANSCRIPT SEGMENT:
$chunk
""".trimIndent()

    fun condensePrompt(notes: String): String = """
Condense these evidence notes while preserving every distinct decision, action item, meaningful number, uncertainty and learning/research point.
Remove duplication only. Do not add facts or recommendations.
Return Markdown, maximum 350 words.

EVIDENCE NOTES:
$notes
""".trimIndent()

    fun finalPrompt(source: RecordingSource, evidenceNotes: String): String = """
Create the final structured note for a ${sourceLabel(source)} from the evidence notes below.
Deduplicate repeated points without changing meaning.

Start with:
# <short evidence-based title>

Then use relevant headings from this template:
## Summary
## Decisions
## Action items
## Questions / uncertainties
## Clinical / academic learning points
## Research ideas
## Follow-up

For phone calls, emphasize commitments, decisions and follow-up.
For bedside/academic discussions or imported audio, emphasize the problem/topic discussed, reasoning that was actually stated, decisions, teaching points and unanswered questions.
For voice reflections, emphasize ideas, lessons, questions and next actions.

Rules:
- Do not invent facts or recommendations.
- Preserve uncertainty and qualifiers.
- Do not identify speakers unless explicitly clear in the evidence.
- Do not reproduce identifiers or attempt to reverse redaction tokens.
- Maximum 500 words.

EVIDENCE NOTES:
$evidenceNotes
""".trimIndent()

    fun knowledgeQuestionPrompt(question: String, evidence: String): String = """
Answer the user's knowledge-library question using ONLY the de-identified VoiceGrowth evidence below.
If the evidence does not answer the question, say so explicitly. Do not fill gaps from general knowledge.
Cite supporting recordings inline as [Recording <id>] using the IDs present in the evidence.
Preserve uncertainty and distinguish discussion, decision, action item, and unresolved question.
Maximum 450 words.

QUESTION:
$question

VOICEGROWTH EVIDENCE:
$evidence
""".trimIndent()

    fun dailyEvidencePrompt(chunk: String, chunkIndex: Int, chunkCount: Int): String = """
This is part ${chunkIndex + 1} of $chunkCount from today's de-identified VoiceGrowth notes.
Extract only items explicitly present for a daily digest.
Use compact bullets under relevant headings: Discussions, Decisions, Actions, Learning, Research ideas, Unresolved questions.
Do not add medical advice or infer missing facts. Maximum 300 words.

TODAY'S NOTES:
$chunk
""".trimIndent()

    fun dailyDigestPrompt(evidenceNotes: String): String = """
Create a concise daily VoiceGrowth digest from the evidence notes below.
Start with '# VoiceGrowth Daily Digest'. Then use relevant headings:
## What I discussed
## Decisions / commitments
## Action items
## Learning points
## Research ideas
## Questions to revisit

Deduplicate repeated points. Include only what is supported by the evidence. Do not add recommendations or outside knowledge. Maximum 650 words.

EVIDENCE NOTES:
$evidenceNotes
""".trimIndent()

    private fun sourceLabel(source: RecordingSource): String = when (source) {
        RecordingSource.CALL_RECORDING -> "phone call"
        RecordingSource.MANUAL_DISCUSSION -> "bedside or academic discussion"
        RecordingSource.VOICE_REFLECTION -> "voice reflection"
        RecordingSource.IMPORTED_AUDIO -> "imported audio discussion"
    }
}
