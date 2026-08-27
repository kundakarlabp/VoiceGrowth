package com.voicegrowth.medscribe

/**
 * Conservative cleanup only. It never invents diagnoses, doses, organisms, or plans.
 * The editable transcript remains the source of truth and the audio is retained for verification.
 */
object MedicalText {
    private val replacements = linkedMapOf(
        Regex("\\bampho\\s+tericin\\b", RegexOption.IGNORE_CASE) to "amphotericin",
        Regex("\\bvori\\s+conazole\\b", RegexOption.IGNORE_CASE) to "voriconazole",
        Regex("\\bposa\\s+conazole\\b", RegexOption.IGNORE_CASE) to "posaconazole",
        Regex("\\bisavu\\s+conazole\\b", RegexOption.IGNORE_CASE) to "isavuconazole",
        Regex("\\bmero\\s+penem\\b", RegexOption.IGNORE_CASE) to "meropenem",
        Regex("\\bimipenem\\s+cilastatin\\b", RegexOption.IGNORE_CASE) to "imipenem-cilastatin",
        Regex("\\bpiperacillin\\s+tazobactam\\b", RegexOption.IGNORE_CASE) to "piperacillin-tazobactam",
        Regex("\\bceftazidime\\s+avibactam\\b", RegexOption.IGNORE_CASE) to "ceftazidime-avibactam",
        Regex("\\bmero\\s+penem\\s+vaborbactam\\b", RegexOption.IGNORE_CASE) to "meropenem-vaborbactam",
        Regex("\\btrimethoprim\\s+sulfamethoxazole\\b", RegexOption.IGNORE_CASE) to "trimethoprim-sulfamethoxazole"
    )

    fun clean(text: String): String {
        var out = text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .trim()
        replacements.forEach { (pattern, replacement) -> out = out.replace(pattern, replacement) }
        return out
    }

    fun detectedTopics(text: String): List<String> {
        val lower = text.lowercase()
        return topics.mapNotNull { (label, keys) -> label.takeIf { keys.any(lower::contains) } }
    }

    private val topics = linkedMapOf(
        "Antimicrobial resistance" to listOf("resistan", "mdr", "xdr", "crab", "cre", "carbapenem"),
        "Antimicrobial stewardship" to listOf("de-escal", "antibiotic", "antimicrobial", "duration", "source control"),
        "Transplant / immunocompromised host" to listOf("transplant", "cmv", "valganciclovir", "tacrolimus", "neutropen"),
        "Invasive fungal infection" to listOf("asperg", "mucor", "candida", "amphotericin", "voriconazole", "posaconazole"),
        "Tuberculosis" to listOf("tuberculosis", "rifamp", "isoniazid", "bedaquiline"),
        "HIV" to listOf("hiv", "antiretroviral", "cd4", "viral load"),
        "Sepsis / critical care" to listOf("sepsis", "septic shock", "vasopressor", "lactate", "icu")
    )
}
