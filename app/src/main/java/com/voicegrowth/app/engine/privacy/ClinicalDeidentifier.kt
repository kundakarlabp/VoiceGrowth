package com.voicegrowth.app.engine.privacy

import java.util.regex.Pattern

data class DeidentificationResult(
    val scrubbedText: String,
    val identifiersDetectedCount: Int,
    val detectedIdentifierTypes: List<String>,
    val requiresManualReview: Boolean
)

object ClinicalDeidentifier {
    private data class Rule(val type: String, val pattern: Pattern, val replacement: String)

    private val rules = listOf(
        Rule(
            "Email Address",
            Pattern.compile("\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"),
            "[Email Redacted]"
        ),
        Rule(
            "Phone Number",
            Pattern.compile("(?<!\\d)(?:\\+?91[-.\\s]?)?[6-9]\\d{4}[-.\\s]?\\d{5}(?!\\d)"),
            "[Phone Number Redacted]"
        ),
        Rule(
            "Hospital UHID/MRN",
            Pattern.compile(
                "\\b(?:UHID|MRN|IP|OP|CR|REG|IPD|OPD|HOSP(?:ITAL)?\\s*NO)[#:\\s-]*[A-Z0-9/-]{4,20}\\b",
                Pattern.CASE_INSENSITIVE
            ),
            "[MRN Redacted]"
        ),
        Rule(
            "National ID Pattern",
            Pattern.compile("(?<!\\d)\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)"),
            "[National ID Redacted]"
        ),
        Rule(
            "Date of Birth",
            Pattern.compile(
                "\\b(?:DOB|date of birth|born on)\\s*(?:is|:|-)?\\s*\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b",
                Pattern.CASE_INSENSITIVE
            ),
            "[Date of Birth Redacted]"
        ),
        Rule(
            "Patient Name Introduction",
            Pattern.compile(
                "\\b(patient(?:'s)?\\s+(?:name\\s+)?(?:is|:) |patient name is |attendant of |relative of |Mr\\.? |Mrs\\.? |Ms\\.? |Sri |Smt\\.? )([A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]+){0,2})",
                Pattern.CASE_INSENSITIVE
            ),
            "\$1[Name Redacted]"
        )
    )

    fun process(rawText: String, enabled: Boolean): DeidentificationResult {
        if (!enabled) {
            return DeidentificationResult(rawText, 0, emptyList(), requiresManualReview = true)
        }

        var result = rawText
        var totalMatches = 0
        val detectedTypes = linkedSetOf<String>()

        rules.forEach { rule ->
            val matcher = rule.pattern.matcher(result)
            var matches = 0
            while (matcher.find()) matches++
            if (matches > 0) {
                totalMatches += matches
                detectedTypes += rule.type
                result = rule.pattern.matcher(result).replaceAll(rule.replacement)
            }
        }

        // Pattern-based de-identification reduces risk but cannot prove that free text is fully anonymous.
        return DeidentificationResult(
            scrubbedText = result,
            identifiersDetectedCount = totalMatches,
            detectedIdentifierTypes = detectedTypes.toList(),
            requiresManualReview = true
        )
    }
}
