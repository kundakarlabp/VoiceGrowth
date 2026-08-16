package com.voicegrowth.app.engine.transcription

object MedicalVocabularyPrompt {
    val TERMS: List<String> = listOf(
        "Pseudomonas aeruginosa", "DTR Pseudomonas", "Acinetobacter baumannii", "CRAB",
        "Klebsiella pneumoniae", "Enterobacter cloacae", "Stenotrophomonas maltophilia",
        "Burkholderia cepacia", "NDM", "OXA-48", "KPC", "VIM", "IMP", "ESBL",
        "Enterococcus faecium", "Enterococcus faecalis", "VRE", "Staphylococcus aureus",
        "MRSA", "MSSA", "Streptococcus pneumoniae", "CoNS",
        "Mucorales", "Rhizopus", "Mucormycosis", "Aspergillus fumigatus", "Aspergillus flavus",
        "Candida auris", "Candida albicans", "Cryptococcus neoformans", "Cytomegalovirus",
        "CMV", "Epstein-Barr virus", "EBV", "BK virus", "Pneumocystis jirovecii", "PJP",
        "Ceftazidime-avibactam", "Ceftolozane-tazobactam", "Meropenem-vaborbactam",
        "Imipenem-cilastatin-relebactam", "Cefiderocol", "Aztreonam-avibactam", "Aztreonam",
        "Colistin", "Polymyxin B", "Fosfomycin", "Tigecycline", "Eravacycline", "Minocycline",
        "Daptomycin", "Linezolid", "Tedizolid", "Vancomycin", "Teicoplanin", "Piperacillin-tazobactam",
        "Meropenem", "Imipenem", "Ertapenem", "Cefepime", "Amikacin", "Tobramycin",
        "Liposomal Amphotericin B", "Posaconazole", "Isavuconazole", "Voriconazole", "Fluconazole",
        "Anidulafungin", "Caspofungin", "Micafungin", "Ganciclovir", "Valganciclovir",
        "Letermovir", "Maribavir", "Foscarnet", "Cidofovir", "Cotrimoxazole", "Trimethoprim-sulfamethoxazole",
        "MIC", "EUCAST", "CLSI", "PK/PD", "fT>MIC", "AUC/MIC", "Cmax/MIC", "extended infusion",
        "continuous infusion", "loading dose", "creatinine clearance", "source control",
        "procalcitonin", "galactomannan", "beta-D-glucan", "BioFire FilmArray", "multiplex PCR",
        "MALDI-TOF", "transplant infectious diseases", "SOT", "HSCT", "febrile neutropenia",
        "CRBSI", "HAP", "VAP", "intra-abdominal infection", "infective endocarditis"
    )

    fun getBiasingPrompt(): String = TERMS.joinToString(", ")
}
