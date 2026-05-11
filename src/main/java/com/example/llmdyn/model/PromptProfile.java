package com.example.llmdyn.model;

import java.util.Locale;

public enum PromptProfile {
    ENHANCED,
    SIMPLE;

    public static PromptProfile from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ENHANCED;
        }

        try {
            return PromptProfile.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid promptProfile: " + raw + ". Allowed values: ENHANCED, SIMPLE"
            );
        }
    }
}

