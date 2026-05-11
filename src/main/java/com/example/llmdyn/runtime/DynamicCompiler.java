
package com.example.llmdyn.runtime;

import javax.tools.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class DynamicCompiler {
    public boolean compile(Path outputDir, Path sourceFile) throws IOException {
        Files.createDirectories(outputDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("No system Java compiler available.");
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> cu = fm.getJavaFileObjectsFromFiles(List.of(sourceFile.toFile()));
            return compiler.getTask(null, fm, null, List.of("-d", outputDir.toString()), null, cu).call();
        }
    }

    public CompilationResult compileMultiple(Path baseDir, List<Path> sourceFiles) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available.");
        }

        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return new CompilationResult(
                    false,
                    List.of(new CompilationDiagnostic(
                            "ERROR",
                            null,
                            -1,
                            -1,
                            "No source files were generated from LLM output. Likely extraction failure."
                    ))
            );
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> sourcePaths = sourceFiles.stream()
                    .map(Path::toString)
                    .collect(Collectors.toList());

            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromStrings(sourcePaths);

            List<String> options = Arrays.asList(
                    "-d", baseDir.toString(),
                    "-cp", System.getProperty("java.class.path")
            );

            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);
            boolean success = task.call();

            List<CompilationDiagnostic> diagnosticRows = toDiagnostics(diagnostics.getDiagnostics());
            if (!success && diagnosticRows.isEmpty()) {
                diagnosticRows = List.of(new CompilationDiagnostic("ERROR", null, -1, -1,
                        "Compilation failed without diagnostics."));
            }

            return new CompilationResult(success, diagnosticRows);
        }
    }

    private List<CompilationDiagnostic> toDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<CompilationDiagnostic> rows = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            String source = d.getSource() == null ? null : d.getSource().getName();
            String message = d.getMessage(Locale.ROOT);
            rows.add(new CompilationDiagnostic(
                    d.getKind().name(),
                    source,
                    d.getLineNumber(),
                    d.getColumnNumber(),
                    message
            ));
        }
        return rows;
    }

    public static class CompilationResult {
        private final boolean success;
        private final List<CompilationDiagnostic> diagnostics;

        public CompilationResult(boolean success, List<CompilationDiagnostic> diagnostics) {
            this.success = success;
            this.diagnostics = diagnostics;
        }

        public boolean isSuccess() {
            return success;
        }

        public List<CompilationDiagnostic> getDiagnostics() {
            return diagnostics;
        }

        public String getPrimaryMessage() {
            if (diagnostics == null || diagnostics.isEmpty()) {
                return "Compilation failed";
            }
            CompilationDiagnostic first = diagnostics.get(0);
            if (first.getLine() > 0) {
                return first.getMessage() + " (line " + first.getLine() + ")";
            }
            return first.getMessage();
        }
    }

    public static class CompilationDiagnostic {
        private final String kind;
        private final String source;
        private final long line;
        private final long column;
        private final String message;

        public CompilationDiagnostic(String kind, String source, long line, long column, String message) {
            this.kind = kind;
            this.source = source;
            this.line = line;
            this.column = column;
            this.message = message;
        }

        public String getKind() {
            return kind;
        }

        public String getSource() {
            return source;
        }

        public long getLine() {
            return line;
        }

        public long getColumn() {
            return column;
        }

        public String getMessage() {
            return message;
        }
    }
}
