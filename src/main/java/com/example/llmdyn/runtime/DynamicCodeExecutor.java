// Java
package com.example.llmdyn.runtime;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Entity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Service
@SuppressWarnings("unused")
public class DynamicCodeExecutor {

    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BeanRegistrar beanRegistrar;

    // Use the shared, Spring-managed BeanRegistrar
    public DynamicCodeExecutor(BeanRegistrar beanRegistrar) {
        this.beanRegistrar = beanRegistrar;
    }

    @SuppressWarnings("unused")
    public String compileLoadAndRegister(String fullClassName, String sourceCode, Path baseOutputDir) throws Exception {
        return compileLoadAndRegister(fullClassName, sourceCode, baseOutputDir, null);
    }

    public String compileLoadAndRegister(String fullClassName,
                                         String sourceCode,
                                         Path baseOutputDir,
                                         String runLabel) throws Exception {
        Map<String, String> classFiles = extractClasses(sourceCode);
        System.out.println("[GEN-EXTRACT] Requested main class: " + fullClassName);
        System.out.println("[GEN-EXTRACT] Extracted classes: "
                + (classFiles.isEmpty() ? "<none>" : String.join(", ", classFiles.keySet())));
        if (classFiles.isEmpty()) {
            String preview = sourceCode.trim();
            if (preview.length() > 300) {
                preview = preview.substring(0, 300) + "...";
            }
            throw new DynamicCompilationException(
                    "No compilable class extracted from generated output. Preview: " + preview,
                    List.of(new DynamicCompiler.CompilationDiagnostic(
                            "ERROR",
                            null,
                            -1,
                            -1,
                            "No class/interface/enum declaration found in generated code."
                    ))
            );
        }

        classFiles = normalizePrimaryClass(fullClassName, sourceCode, classFiles);
        return compileLoadAndRegisterMultiple(classFiles, baseOutputDir, runLabel);
    }

    private Map<String, String> normalizePrimaryClass(String requestedFullClassName,
                                                      String sourceCode,
                                                      Map<String, String> extractedClasses) {
        if (requestedFullClassName == null || requestedFullClassName.isBlank() || extractedClasses.isEmpty()) {
            return extractedClasses;
        }

        if (extractedClasses.containsKey(requestedFullClassName)) {
            return extractedClasses;
        }

        String available = String.join(", ", extractedClasses.keySet());
        String message = "Requested main class was not generated: " + requestedFullClassName
                + ". Extracted classes: " + available;
        throw new DynamicCompilationException(
                message,
                List.of(new DynamicCompiler.CompilationDiagnostic(
                        "ERROR",
                        null,
                        -1,
                        -1,
                        message
                ))
        );
    }

    private String compileLoadAndRegisterMultiple(Map<String, String> classFiles,
                                                  Path baseOutputDir,
                                                  String runLabel) throws Exception {
        //clearBaseOutputDirectory(baseOutputDir);
        String id = buildRunFolderName(runLabel);
        Path work = baseOutputDir.resolve(id);
        Files.createDirectories(work);

        List<Path> sourceFiles = new ArrayList<>();

        // Write all source files
        for (Map.Entry<String, String> entry : classFiles.entrySet()) {
            String fullClassName = entry.getKey();
            String classCode = entry.getValue();

            String packagePath;
            String simpleName;

            if (fullClassName.contains(".")) {
                packagePath = fullClassName.substring(0, fullClassName.lastIndexOf('.')).replace('.', '/');
                simpleName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);
            } else {
                packagePath = "";
                simpleName = fullClassName;
            }

            Path pkgPath = packagePath.isEmpty() ? work : work.resolve(packagePath);
            Files.createDirectories(pkgPath);
            Path sourceFile = pkgPath.resolve(simpleName + ".java");
            Files.writeString(sourceFile, classCode);
            sourceFiles.add(sourceFile);
        }

        // Compile all classes
        DynamicCompiler compiler = new DynamicCompiler();
        DynamicCompiler.CompilationResult compilationResult = compiler.compileMultiple(work, sourceFiles);
        if (!compilationResult.isSuccess()) {
            throw new DynamicCompilationException(
                    compilationResult.getPrimaryMessage(),
                    compilationResult.getDiagnostics()
            );
        }

        // Load all classes
        Map<String, Class<?>> loadedClasses = new LinkedHashMap<>();

        try (DynamicClassLoader loader = new DynamicClassLoader(work, this.getClass().getClassLoader())) {
            for (String fullClassName : classFiles.keySet()) {
                Class<?> clazz = loader.loadClass(fullClassName);
                loadedClasses.put(fullClassName, clazz);
            }

            // Separate entities, repositories, and other beans
            List<Map.Entry<String, Class<?>>> entities = new ArrayList<>();
            List<Map.Entry<String, Class<?>>> repositories = new ArrayList<>();
            List<Map.Entry<String, Class<?>>> others = new ArrayList<>();

            for (Map.Entry<String, Class<?>> entry : loadedClasses.entrySet()) {
                Class<?> clazz = entry.getValue();
                if (clazz.isAnnotationPresent(Entity.class)) {
                    entities.add(entry);
                } else if (JpaRepository.class.isAssignableFrom(clazz)) {
                    repositories.add(entry);
                } else {
                    others.add(entry);
                }
            }

            // Step 1: Register all entities first
            for (Map.Entry<String, Class<?>> entry : entities) {
                String beanName = generateBeanName(entry.getValue());
                beanRegistrar.registerBean(entry.getValue(), beanName);
            }

            // Step 2: Recreate EntityManagerFactory with all entities
            if (!entities.isEmpty()) {
                beanRegistrar.recreateEntityManagerFactory();
            }

            // Step 3: Register repositories
            for (Map.Entry<String, Class<?>> entry : repositories) {
                String beanName = generateBeanName(entry.getValue());
                beanRegistrar.registerBean(entry.getValue(), beanName);
            }

            // Step 4: Register other beans (services, controllers, etc.) or track non-beans
            for (Map.Entry<String, Class<?>> entry : others) {
                String beanName = generateBeanName(entry.getValue());
                beanRegistrar.registerBean(entry.getValue(), beanName);
            }
        }

        return "SUCCESS";
    }

    private void clearBaseOutputDirectory(Path baseOutputDir) throws Exception {
        if (baseOutputDir == null) {
            throw new IllegalArgumentException("Base output directory must not be null.");
        }

        if (Files.exists(baseOutputDir)) {
            if (!Files.isDirectory(baseOutputDir)) {
                throw new IllegalStateException("Configured output path is not a directory: " + baseOutputDir);
            }

            try (Stream<Path> walk = Files.walk(baseOutputDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .filter(path -> !path.equals(baseOutputDir))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ex) {
                                throw new RuntimeException("Failed to delete generated output path: " + path, ex);
                            }
                        });
            }
        }

        Files.createDirectories(baseOutputDir);
        System.out.println("[GEN-CLEAN] Cleared generated classes directory: " + baseOutputDir.toAbsolutePath());
    }

    private String buildRunFolderName(String runLabel) {
        String ts = LocalDateTime.now().format(RUN_TS);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String normalized = normalizeLabel(runLabel);
        return normalized.isBlank() ? "run-" + ts + "-" + suffix : normalized + "-" + ts + "-" + suffix;
    }

    private String normalizeLabel(String runLabel) {
        if (runLabel == null || runLabel.isBlank()) {
            return "";
        }
        String normalized = runLabel.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        normalized = normalized.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return normalized.length() > 40 ? normalized.substring(0, 40) : normalized;
    }

    private String generateBeanName(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    private Map<String, String> extractClasses(String sourceCode) {
        Map<String, String> classes = new LinkedHashMap<>();
        if (sourceCode == null || sourceCode.isBlank()) {
            return classes;
        }

        String normalizedSource = normalizeStructuredPayload(sourceCode);

        Map<String, String> jsonClasses = extractClassesFromJsonArray(normalizedSource);
        if (!jsonClasses.isEmpty()) {
            return jsonClasses;
        }

        ParsedSourceUnit parsed = parseSourceUnit(normalizedSource);
        if (parsed != null) {
            extractClassesFromParsedUnit(parsed, classes);
        }

        if (!classes.isEmpty()) {
            return classes;
        }

        // Fallback for concatenated multi-package output: parse each package-delimited source unit with AST.
        for (String unitSource : splitSourceUnits(normalizedSource)) {
            ParsedSourceUnit unit = parseSourceUnit(unitSource);
            if (unit != null) {
                extractClassesFromParsedUnit(unit, classes);
            }
        }

        return classes;
    }

    private Map<String, String> extractClassesFromJsonArray(String sourceCode) {
        Map<String, String> classes = new LinkedHashMap<>();
        String trimmed = sourceCode.trim();
        if (trimmed.isBlank()) {
            return classes;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(trimmed);
            JsonNode items = root;
            if (root.isObject()) {
                items = findJsonClassArrayNode(root);
            }
            if (items == null || !items.isArray()) {
                return classes;
            }

            for (JsonNode item : items) {
                if (item == null || !item.isObject()) {
                    continue;
                }

                String classSource = unwrapSingleFencedBlock(firstNonBlankText(item,
                        "sourceCode", "code", "content", "source"));
                if (classSource == null || classSource.isBlank()) {
                    continue;
                }

                String fullClassName = firstNonBlankText(item, "fullClassName", "className", "name");
                String resolvedClassName = resolveClassName(fullClassName, classSource);
                if (resolvedClassName == null || resolvedClassName.isBlank()) {
                    continue;
                }

                classes.put(resolvedClassName.trim(), classSource);
            }
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }

        return classes;
    }

    private String firstNonBlankText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isNull()) {
                String value = field.asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private JsonNode findJsonClassArrayNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }

        if (isJsonClassItem(root)) {
            return OBJECT_MAPPER.createArrayNode().add(root);
        }

        for (String field : List.of("files", "classes", "items", "sources", "result")) {
            JsonNode node = root.get(field);
            if (node != null && node.isArray()) {
                return node;
            }
        }

        return null;
    }

    private boolean isJsonClassItem(JsonNode node) {
        return firstNonBlankText(node, "sourceCode", "code", "content", "source") != null;
    }

    private String resolveClassName(String fullClassName, String classSource) {
        if (fullClassName != null && !fullClassName.isBlank() && !"java".equals(fullClassName.trim())) {
            return fullClassName.trim();
        }

        ParsedSourceUnit parsed = parseSourceUnit(classSource);
        if (parsed == null) {
            return fullClassName;
        }

        String packageName = parsed.compilationUnit.getPackageName() == null
                ? ""
                : parsed.compilationUnit.getPackageName().toString();
        for (Tree typeDecl : parsed.compilationUnit.getTypeDecls()) {
            if (typeDecl instanceof ClassTree classTree && isSupportedTopLevelType(classTree)) {
                String className = classTree.getSimpleName() == null ? "" : classTree.getSimpleName().toString();
                if (!className.isBlank()) {
                    return packageName.isBlank() ? className : packageName + "." + className;
                }
            }
        }

        return fullClassName;
    }

    private String normalizeStructuredPayload(String raw) {
        if (raw == null) {
            return "";
        }

        String trimmed = unwrapSingleFencedBlock(raw).trim();
        if (trimmed.isBlank()) {
            return trimmed;
        }

        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(trimmed);
                JsonNode arrayNode = findJsonClassArrayNode(root);
                if (arrayNode != null && arrayNode.isArray()) {
                    return arrayNode.toString();
                }
            } catch (Exception ignored) {
                // Keep original when payload is not valid JSON.
            }
        }

        return trimmed;
    }

    private String unwrapSingleFencedBlock(String raw) {
        if (raw == null) {
            return null;
        }

        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }

        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline < 0) {
            return trimmed;
        }

        int closingFence = trimmed.lastIndexOf("```");
        if (closingFence <= firstNewline) {
            return trimmed;
        }

        return trimmed.substring(firstNewline + 1, closingFence).trim();
    }

    private void extractClassesFromParsedUnit(ParsedSourceUnit parsed,
                                              Map<String, String> classes) {
        CompilationUnitTree compilationUnit = parsed.compilationUnit;
        String packageName = compilationUnit.getPackageName() == null
                ? ""
                : compilationUnit.getPackageName().toString();

        List<? extends Tree> typeDecls = compilationUnit.getTypeDecls();
        if (typeDecls.isEmpty()) {
            return;
        }

        long firstTypeStart = Long.MAX_VALUE;
        for (Tree typeDecl : typeDecls) {
            if (typeDecl instanceof ClassTree classTree && isSupportedTopLevelType(classTree)) {
                long start = parsed.sourcePositions.getStartPosition(compilationUnit, typeDecl);
                if (start >= 0 && start < firstTypeStart) {
                    firstTypeStart = start;
                }
            }
        }

        String prefix = "";
        if (firstTypeStart > 0 && firstTypeStart <= parsed.sourceCode.length()) {
            prefix = parsed.sourceCode.substring(0, (int) firstTypeStart);
        }

        for (Tree typeDecl : typeDecls) {
            if (!(typeDecl instanceof ClassTree classTree) || !isSupportedTopLevelType(classTree)) {
                continue;
            }

            String className = classTree.getSimpleName() == null ? "" : classTree.getSimpleName().toString();
            if (className.isBlank()) {
                continue;
            }

            long start = parsed.sourcePositions.getStartPosition(compilationUnit, typeDecl);
            long end = parsed.sourcePositions.getEndPosition(compilationUnit, typeDecl);
            String typeSource = extractSourceSlice(parsed.sourceCode, start, end);
            if (typeSource.isBlank()) {
                continue;
            }

            String fullClassName = packageName.isBlank() ? className : packageName + "." + className;
            classes.put(fullClassName, prefix + typeSource);
        }
    }

    private boolean isSupportedTopLevelType(ClassTree classTree) {
        Tree.Kind kind = classTree.getKind();
        return kind == Tree.Kind.CLASS || kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ENUM;
    }

    private List<String> splitSourceUnits(String sourceCode) {
        String[] parts = sourceCode.split("(?=package\\s+[\\w.]+;)");
        List<String> units = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                units.add(part);
            }
        }
        if (units.isEmpty() && !sourceCode.isBlank()) {
            units.add(sourceCode);
        }
        return units;
    }

    private String extractSourceSlice(String sourceCode, long start, long end) {
        if (start < 0 || end < 0 || start >= sourceCode.length()) {
            return "";
        }

        int safeStart = (int) Math.max(0, Math.min(start, sourceCode.length()));
        int safeEnd = (int) Math.max(safeStart, Math.min(end, sourceCode.length()));
        return sourceCode.substring(safeStart, safeEnd).trim();
    }

    private ParsedSourceUnit parseSourceUnit(String sourceCode) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return null;
            }

            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                JavaFileObject sourceFile = new InMemoryJavaSource("Generated.java", sourceCode);
                JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null, List.of(sourceFile));
                Iterator<? extends CompilationUnitTree> iterator = task.parse().iterator();
                if (!iterator.hasNext()) {
                    return null;
                }

                CompilationUnitTree compilationUnit = iterator.next();
                Trees trees = Trees.instance(task);
                SourcePositions sourcePositions = trees.getSourcePositions();
                return new ParsedSourceUnit(sourceCode, compilationUnit, sourcePositions);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static final class ParsedSourceUnit {
        private final String sourceCode;
        private final CompilationUnitTree compilationUnit;
        private final SourcePositions sourcePositions;

        private ParsedSourceUnit(String sourceCode,
                                 CompilationUnitTree compilationUnit,
                                 SourcePositions sourcePositions) {
            this.sourceCode = sourceCode;
            this.compilationUnit = compilationUnit;
            this.sourcePositions = sourcePositions;
        }
    }

    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String source;

        private InMemoryJavaSource(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension), JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

}

