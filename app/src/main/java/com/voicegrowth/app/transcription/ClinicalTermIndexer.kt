package com.voicegrowth.app.transcription

/**
 * Evidence-candidate index ONLY. Every hit is from the unverified ASR text,
 * never a confirmed medication exposure, culture result or diagnosis.
 * No patient-name/CR-number extraction here.
 */
internal object ClinicalTermIndexer {
    data class Hit(val raw: String, val category: String, val tentativeTerm: String,
                   val startMs: Long, val excerpt: String)
    private data class Rule(val regex: Regex, val category: String, val tentativeTerm: String)
    private val rules = listOf(
        Rule(Regex("\\bmero(?:penem)?\\b", RegexOption.IGNORE_CASE), "drug_mention", "meropenem_possible"),
        Rule(Regex("\\b(vanco(?:mycin)?|teico(?:planin)?)\\b", RegexOption.IGNORE_CASE), "drug_mention", "glycopeptide_possible"),
        Rule(Regex("\\b(colistin|polymyxin|linezolid|amikacin|cefepime|tigecycline|minocycline)\\b", RegexOption.IGNORE_CASE), "drug_mention", "verify_specific_agent"),
        Rule(Regex("\\b(?:bio[ -]?fire)\\b", RegexOption.IGNORE_CASE), "diagnostic_test_mention", "BioFire_possible"),
        Rule(Regex("\\bblood\\s+cultures?\\b", RegexOption.IGNORE_CASE), "diagnostic_test_mention", "blood_culture_possible"),
        Rule(Regex("\\b(CRO|CRAB|CRE|ESBL|MRSA)\\b", RegexOption.IGNORE_CASE), "resistance_shorthand", "context_required")
    )

    fun find(text: String, timestampMs: Long): List<Hit> =
        rules.flatMap { rule ->
            rule.regex.findAll(text).take(15).map {
                Hit(raw = it.value, category = rule.category, tentativeTerm = rule.tentativeTerm,
                    startMs = timestampMs, excerpt = text.take(350))
            }.toList()
        }.take(35)
}
