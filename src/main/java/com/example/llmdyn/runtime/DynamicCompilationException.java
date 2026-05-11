package com.example.llmdyn.runtime;

import java.util.List;

public class DynamicCompilationException extends RuntimeException {
    private final List<DynamicCompiler.CompilationDiagnostic> diagnostics;

    public DynamicCompilationException(String message, List<DynamicCompiler.CompilationDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = diagnostics;
    }

    public List<DynamicCompiler.CompilationDiagnostic> getDiagnostics() {
        return diagnostics;
    }
}

