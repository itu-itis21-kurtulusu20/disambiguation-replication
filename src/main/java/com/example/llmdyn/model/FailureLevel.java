package com.example.llmdyn.model;

public enum FailureLevel {
    LEVEL_1_GENERATION_EXTRACTION("Generation/Extraction"),
    LEVEL_2_COMPILATION("Compilation"),
    LEVEL_3_SPRING_CONTEXT_INIT("Spring Context Init."),
    LEVEL_4_PERSISTENCE_DATABASE("Persistence/Database"),
    LEVEL_5_FUNCTIONAL_MERGED("Functional (L5/L6 merged)"),
    UNCLASSIFIED("Unclassified");

    private final String label;

    FailureLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

