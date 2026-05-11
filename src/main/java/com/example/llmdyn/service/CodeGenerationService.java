package com.example.llmdyn.service;

import com.example.llmdyn.llm.LLMServiceFactory;
import com.example.llmdyn.model.PromptProfile;
import com.example.llmdyn.model.TestCase;
import com.example.llmdyn.runtime.DynamicCodeExecutor;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CodeGenerationService {

    private final LLMServiceFactory llmServiceFactory;
    private final DynamicCodeExecutor dynamicCodeExecutor;
    private final ProjectContextService projectContextService;
    private final ThreadLocal<String> lastGeneratedSource = new ThreadLocal<>();

    @Value("${dynamic.compile.output:./generated-classes}")
    private String compileOutput;

    @Value("${prompt.enhanced.include-jpa-guidelines:true}")
    private boolean includeJpaGuidelinesDefault;

    public CodeGenerationService(LLMServiceFactory llmServiceFactory,
                                 DynamicCodeExecutor dynamicCodeExecutor,
                                 ProjectContextService projectContextService) {
        this.llmServiceFactory = llmServiceFactory;
        this.dynamicCodeExecutor = dynamicCodeExecutor;
        this.projectContextService = projectContextService;
    }

    public String generateAndCompile(String fullClassName, String prompt, List<TestCase.TestScenario> testCases) throws Exception {
        return generateAndCompile(fullClassName, prompt, testCases, PromptProfile.ENHANCED, true);
    }

    public String generateAndCompile(String fullClassName,
                                     String prompt,
                                     List<TestCase.TestScenario> testCases,
                                     PromptProfile promptProfile,
                                     boolean includeProjectContext) throws Exception {
        return generateAndCompile(fullClassName, prompt, testCases, promptProfile, includeProjectContext, null);
    }

    public String generateAndCompile(String fullClassName,
                                     String prompt,
                                     List<TestCase.TestScenario> testCases,
                                     PromptProfile promptProfile,
                                     boolean includeProjectContext,
                                     String runLabel) throws Exception {
        return generateAndCompile(fullClassName, prompt, testCases, promptProfile, includeProjectContext, runLabel, null);
    }

    public String generateAndCompile(String fullClassName,
                                     String prompt,
                                     List<TestCase.TestScenario> testCases,
                                     PromptProfile promptProfile,
                                     boolean includeProjectContext,
                                     String runLabel,
                                     Boolean includeJpaGuidelines) throws Exception {
        PromptProfile effectiveProfile = promptProfile == null ? PromptProfile.ENHANCED : promptProfile;
        boolean effectiveIncludeJpaGuidelines = includeJpaGuidelines == null
                ? includeJpaGuidelinesDefault
                : includeJpaGuidelines;
        String projectContext = includeProjectContext ? projectContextService.getOptimizedProjectContext() : "";

        String methodNames = "";
        if (testCases != null && !testCases.isEmpty()) {
            methodNames = testCases.stream()
                    .map(TestCase.TestScenario::getMethodName)
                    .distinct()
                    .collect(Collectors.joining(", "));
        }
        String methodContracts = buildMethodContracts(testCases);

        String finalPrompt = effectiveProfile == PromptProfile.SIMPLE
                ? buildSimplePrompt(projectContext, prompt, fullClassName, methodNames, methodContracts)
                : buildEnhancedPrompt(projectContext, prompt, fullClassName, methodNames, methodContracts, effectiveIncludeJpaGuidelines, testCases);

        String llmResponse = llmServiceFactory.getService().call(finalPrompt);
        String extractedPayload = extractJavaCode(llmResponse);
        String compilationInput;
        if (isStructuredPayload(extractedPayload)) {
            compilationInput = extractedPayload.trim();
        } else {
            String sanitizedSourceCode = stripAccessorPlaceholderHeaders(extractedPayload);
            validateExtractedJavaSource(sanitizedSourceCode);
            compilationInput = sanitizedSourceCode;
        }
        lastGeneratedSource.set(compilationInput);

        return dynamicCodeExecutor.compileLoadAndRegister(
                fullClassName,
                compilationInput,
                Path.of(compileOutput),
                runLabel
        );
    }

    public String getLastGeneratedSource() {
        return lastGeneratedSource.get();
    }

    public void clearLastGeneratedSource() {
        lastGeneratedSource.remove();
    }

    // -------------------------------------------------------------------------
    // ENHANCED PROMPT
    // -------------------------------------------------------------------------

    private String buildEnhancedPrompt(String projectContext,
                                       String prompt,
                                       String fullClassName,
                                       String methodNames,
                                       String methodContracts,
                                       boolean includeJpaGuidelines,
                                       List<TestCase.TestScenario> testCases) {
        String contextPrefix = projectContext == null || projectContext.isBlank()
                ? ""
                : "Project Context:\n" + projectContext + "\n\n";

        // --- RULE 1: Per-file import self-check (moved to top, highest priority) ---
        String importRules = """
                
                RULE 1 — MANDATORY PER-FILE IMPORT CHECK (failure here causes compilation errors):
                Before finalising EACH sourceCode value in the JSON array, scan that file line by line.
                For every non-primitive type name that appears in the file body, verify there is a
                matching import statement at the top of that same sourceCode string.
                Required imports by type (add ALL that apply to each file):
                  - BigDecimal          → import java.math.BigDecimal;
                  - LocalDate           → import java.time.LocalDate;
                  - LocalDateTime       → import java.time.LocalDateTime;
                  - LocalTime           → import java.time.LocalTime;
                  - HttpStatus          → import org.springframework.http.HttpStatus;
                  - ResponseEntity      → import org.springframework.http.ResponseEntity;
                  - List / Set / Optional / Map → import java.util.List; (etc., or import java.util.*;)
                  - Sort                → import org.springframework.data.domain.Sort;
                  - Page / Pageable     → import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable;
                  - @Entity / @Table / @Id / @GeneratedValue / @Column → import jakarta.persistence.*;
                  - @Service / @Repository / @Component → import org.springframework.stereotype.Service; (etc.)
                  - @Autowired          → import org.springframework.beans.factory.annotation.Autowired;
                  - @RestController / @GetMapping / @PostMapping / etc. → import org.springframework.web.bind.annotation.*;
                  - @Transactional      → import org.springframework.transaction.annotation.Transactional;
                  - @ResponseStatus     → import org.springframework.web.bind.annotation.ResponseStatus;
                  - ResponseStatusException → import org.springframework.web.server.ResponseStatusException;
                  - CascadeType / FetchType / GenerationType → import jakarta.persistence.*;
                VERIFY: After writing each sourceCode, re-read it and confirm every referenced type has an import.
                This check is MANDATORY for every file in the array, not just the main class.
                """;

        // --- RULE 2: JPA guidelines ---
        String jpaGuidelines = includeJpaGuidelines
                ? """
                RULE 2 — JPA STRUCTURE REQUIREMENTS:
                  - Entity must have @Entity, @Table, @Id, @GeneratedValue annotations.
                  - Repository interface MUST be annotated with @Repository and extend JpaRepository<X, Long>.
                  - Service must be annotated with @Service and use @Transactional on write methods.
                  - For SQL DECIMAL/NUMERIC columns use BigDecimal (never Double/Float).
                  - Never use @Column(scale=...) or @Column(precision=..., scale=...) on Double/Float fields.
                  - For large text columns (e.g., TEXT in PostgreSQL), always use @Lob and do not use columnDefinition.
                  - For each @Entity X used by the main service, generate XRepository in
                    com.example.llmdyn.dynamic.repository extending JpaRepository<X, Long>.
                """
                : "RULE 2 — JPA-specific constraints are disabled unless explicitly requested.\n";

        // --- RULE 3: Service method signatures (ban Map<String,Object> params) ---
        String serviceSignatureRules = """
                RULE 3 — SERVICE METHOD SIGNATURES (HARD CONSTRAINT):
                  - Service methods MUST accept and return typed entity/DTO objects directly.
                    CORRECT:   public Employee createEmployee(Employee employee) { ... }
                    FORBIDDEN: public Employee createEmployee(Map<String, Object> map) { ... }
                  - Using Map<String, Object> as a method parameter bypasses type safety and
                    causes missing-import compilation failures for cast targets (BigDecimal, LocalDate, etc.).
                  - The test case args shown in Rule 9 use object notation (e.g., Employee {name:"Alice"})
                    to represent a typed entity instance — implement the method to accept that entity type,
                    NOT a Map.
                """;

        // --- RULE 4: Null-safety in service methods ---
        String nullSafetyRules = """
                RULE 4 — NULL SAFETY (HARD CONSTRAINT):
                  - Service methods must NEVER return null for entity lookups.
                  - Always use: .orElseThrow(() -> new ResourceNotFoundException("... not found with id: " + id))
                  - Returning null from getById-style methods causes NullPointerException in callers.
                  - The delete method must also verify existence before deleting:
                    if (!repository.existsById(id)) { throw new ResourceNotFoundException(...); }
                    repository.deleteById(id);
                """;

        // --- RULE 5: Controller HTTP status codes ---
        String httpStatusRules = """
                RULE 5 — CONTROLLER HTTP STATUS CODES:
                  - POST   (create) → return new ResponseEntity<>(created, HttpStatus.CREATED);   // 201
                  - GET    (read)   → return ResponseEntity.ok(entity);                            // 200
                  - PUT    (update) → return ResponseEntity.ok(updated);                           // 200
                  - DELETE         → return ResponseEntity.noContent().build();                    // 204
                """;

        // --- RULE 6: Exception handling ---
        String exceptionRules = """
                RULE 6 — EXCEPTION HANDLING:
                  - If any generated class references a custom exception (e.g. ResourceNotFoundException),
                    you MUST include it as a separate JSON array item in package
                    com.example.llmdyn.dynamic.exception, with its fullClassName declared exactly.
                  - Every file that references the exception MUST import it explicitly:
                    import com.example.llmdyn.dynamic.exception.ResourceNotFoundException;
                  - If you reference an exception but do NOT include its implementation as an array item,
                    use org.springframework.web.server.ResponseStatusException with HttpStatus.NOT_FOUND instead.
                  - Exception class must be annotated with @ResponseStatus(HttpStatus.NOT_FOUND) and
                    extend RuntimeException. It must be compilable.
                """;

        return contextPrefix +
                "User Request:\n" + prompt + "\n\n" +
                importRules + "\n" +
                jpaGuidelines + "\n" +
                serviceSignatureRules + "\n" +
                nullSafetyRules + "\n" +
                httpStatusRules + "\n" +
                exceptionRules + "\n" +
                "OUTPUT FORMAT CONSTRAINTS:\n" +
                "  - The requested main class MUST be named exactly: " + fullClassName + "\n" +
                (!methodNames.isEmpty() ? "  - Required method names: " + methodNames + "\n" : "") +
                "  - Return ONLY a JSON array. No explanations, markdown formatting, or code blocks.\n" +
                "  - Each array item must contain 'fullClassName' and 'sourceCode'.\n" +
                "  - Keep the current package structure; each array item may use its own package declaration.\n" +
                "  - Do NOT put multiple package declarations in a single sourceCode value.\n" +
                "  - All generated classes must stay under the package root com.example.llmdyn.dynamic\n" +
                "    (e.g. .model, .repository, .service, .controller, .exception).\n" +
                "  - HARD CONSTRAINT: array[0].fullClassName MUST be exactly " + fullClassName + "\n" +
                "  - HARD CONSTRAINT: array[0].sourceCode MUST declare package " + packagePart(fullClassName) +
                " and public class " + simpleNamePart(fullClassName) + ".\n" +
                "  - If the class has fields, generate real public getter and setter methods for every\n" +
                "    non-static field, with full method bodies.\n" +
                "    Example: public String getName() { return name; }\n" +
                "             public void setName(String name) { this.name = name; }\n" +
                "  - Do NOT replace accessors with placeholder comments ('// Getters and Setters',\n" +
                "    '// getters/setters', '// accessors', 'TODO', or '...').\n" +
                "  - HARD CONSTRAINT: Do NOT import or reference generated domain/service/repository/\n" +
                "    controller classes outside com.example.llmdyn.dynamic.*\n" +
                "  - HARD CONSTRAINT: Every referenced generated type must be included as an item in\n" +
                "    the same JSON array.\n" +
                (!methodContracts.isBlank()
                        ? "  - Method signature contract (must be callable with these exact arities/shapes):\n" + methodContracts + "\n"
                        : "") +
                "  - Test case args below use typed entity notation (e.g. Employee {name:\"Alice\"}) —\n" +
                "    implement methods to accept the typed entity, NOT Map<String,Object>:" +
                buildTestCaseExamples(testCases, fullClassName) + "\n" +
                (!methodNames.isBlank()
                        ? "  - Each required method above must be implemented on the requested main class\n" +
                          "    with exactly the tested arity (do not add mandatory extra parameters).\n"
                        : "") +
                "\nExample output:\n" + buildJsonArrayExample(fullClassName);
    }

    // -------------------------------------------------------------------------
    // SIMPLE PROMPT
    // -------------------------------------------------------------------------

    private String buildSimplePrompt(String projectContext,
                                     String prompt,
                                     String fullClassName,
                                     String methodNames,
                                     String methodContracts) {
        String contextPrefix = projectContext == null || projectContext.isBlank()
                ? ""
                : "Project Context:\n" + projectContext + "\n\n";

        // Same per-file import check, condensed for simple profile
        String importRules = """
                
                MANDATORY PER-FILE IMPORT CHECK (failure here causes compilation errors):
                Before finalising EACH sourceCode value, scan that file for every non-primitive type
                and verify a matching import exists at the top of that same sourceCode string.
                  - BigDecimal          → import java.math.BigDecimal;
                  - LocalDate           → import java.time.LocalDate;
                  - LocalDateTime       → import java.time.LocalDateTime;
                  - LocalTime           → import java.time.LocalTime;
                  - HttpStatus          → import org.springframework.http.HttpStatus;
                  - ResponseEntity      → import org.springframework.http.ResponseEntity;
                  - List/Set/Optional/Map → import java.util.List; (etc.)
                  - Sort                → import org.springframework.data.domain.Sort;
                  - Page/Pageable       → import org.springframework.data.domain.Page; / Pageable;
                  - @Entity etc.        → import jakarta.persistence.*;
                  - @Service etc.       → import org.springframework.stereotype.Service; (etc.)
                  - @Autowired          → import org.springframework.beans.factory.annotation.Autowired;
                  - @RestController etc.→ import org.springframework.web.bind.annotation.*;
                  - @Transactional      → import org.springframework.transaction.annotation.Transactional;
                  - @ResponseStatus     → import org.springframework.web.bind.annotation.ResponseStatus;
                VERIFY: Re-read each sourceCode after writing it and confirm all referenced types are imported.
                """;

        String exceptionRules = """
                EXCEPTION HANDLING RULES:
                  - If any generated class references a custom exception (e.g. ResourceNotFoundException),
                    MUST include it as a separate JSON array item in package com.example.llmdyn.dynamic.exception.
                  - Every file referencing the exception MUST import it explicitly.
                  - If you reference an exception but do NOT include its implementation, use
                    org.springframework.web.server.ResponseStatusException with HttpStatus.NOT_FOUND instead.
                """;

        String serviceSignatureRules = """
                SERVICE METHOD SIGNATURE RULES (HARD CONSTRAINT):
                  - Service methods MUST accept typed entity/DTO objects directly, NOT Map<String, Object>.
                  - CORRECT:   public Employee createEmployee(Employee employee)
                  - FORBIDDEN: public Employee createEmployee(Map<String, Object> map)
                """;

        String nullSafetyRules = """
                NULL SAFETY RULES (HARD CONSTRAINT):
                  - Never return null from entity lookup methods.
                  - Always use .orElseThrow(() -> new ResourceNotFoundException(...))
                """;

        String httpStatusRules = """
                CONTROLLER HTTP STATUS RULES:
                  - POST (create) → HttpStatus.CREATED (201)
                  - GET  (read)   → ResponseEntity.ok(...) (200)
                  - PUT  (update) → ResponseEntity.ok(...) (200)
                  - DELETE        → ResponseEntity.noContent().build() (204)
                """;

        return contextPrefix +
                "Task:\n" + prompt + "\n\n" +
                importRules + "\n" +
                serviceSignatureRules + "\n" +
                nullSafetyRules + "\n" +
                httpStatusRules + "\n" +
                exceptionRules + "\n" +
                "Requested main class: " + fullClassName + "\n" +
                (!methodNames.isEmpty() ? "Required method names: " + methodNames + "\n" : "") +
                "Return ONLY a JSON array. No explanations, markdown formatting, or code blocks.\n" +
                "Each array item must contain 'fullClassName' and 'sourceCode'.\n" +
                "Keep the current package structure; each array item may use its own package declaration.\n" +
                "Do NOT put multiple package declarations in a single sourceCode value.\n" +
                "All generated classes must stay under the package root com.example.llmdyn.dynamic\n" +
                "(e.g. .model, .repository, .service, .controller, .exception).\n" +
                "The requested main class must appear in the array exactly as: " + fullClassName + "\n" +
                "The package declaration inside that sourceCode value must match the package portion of " + fullClassName + " exactly.\n" +
                "If the class has fields, generate real public getter and setter methods for every non-static field.\n" +
                "   Example: public String getName() { return name; }\n" +
                "            public void setName(String name) { this.name = name; }\n" +
                "Do not replace accessors with placeholder comments ('// Getters and Setters', 'TODO', '...').\n" +
                "HARD CONSTRAINT: array[0].fullClassName MUST be exactly " + fullClassName + "\n" +
                "HARD CONSTRAINT: array[0].sourceCode MUST declare package " + packagePart(fullClassName) + " and public class " + simpleNamePart(fullClassName) + ".\n" +
                "HARD CONSTRAINT: Do NOT import or reference generated classes outside com.example.llmdyn.dynamic.*\n" +
                "HARD CONSTRAINT: Every referenced generated type must be included as an item in the same JSON array.\n" +
                (!methodContracts.isBlank()
                        ? "Method signature contract (must be callable with these exact arities/shapes):\n" + methodContracts + "\n"
                        : "") +
                (!methodNames.isBlank()
                        ? "Each required method above must be implemented on the requested main class with exactly the tested arity.\n"
                        : "") +
                "\nExample output:\n" + buildJsonArrayExample(fullClassName);
    }

    // -------------------------------------------------------------------------
    // CONTRACT / EXAMPLE BUILDERS (unchanged)
    // -------------------------------------------------------------------------

    private String buildMethodContracts(List<TestCase.TestScenario> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return "";
        }

        Map<String, Set<String>> contractsByMethod = new LinkedHashMap<>();
        for (TestCase.TestScenario scenario : testCases) {
            if (scenario == null || scenario.getMethodName() == null || scenario.getMethodName().isBlank()) {
                continue;
            }

            List<Object> args = scenario.getArgs();
            int arity = args == null ? 0 : args.size();
            String shape = args == null || args.isEmpty()
                    ? ""
                    : args.stream().map(this::describeArgShape).collect(Collectors.joining(", "));
            String signature = scenario.getMethodName() + "(" + shape + ")" + " [arity=" + arity + "]";

            contractsByMethod
                    .computeIfAbsent(scenario.getMethodName(), key -> new LinkedHashSet<>())
                    .add(signature);
        }

        return contractsByMethod.values().stream()
                .flatMap(Set::stream)
                .map(signature -> " - " + signature)
                .collect(Collectors.joining("\n"));
    }

    private String buildTestCaseExamples(List<TestCase.TestScenario> testCases, String fullClassName) {
        if (testCases == null || testCases.isEmpty()) {
            return "";
        }

        String entityName = extractEntityName(fullClassName);

        StringBuilder sb = new StringBuilder("\n");
        for (TestCase.TestScenario scenario : testCases) {
            sb.append("   - ").append(scenario.getMethodName()).append("(");
            if (scenario.getArgs() != null && !scenario.getArgs().isEmpty()) {
                List<String> argStrings = scenario.getArgs().stream()
                        .map(arg -> descriptiveArgString(arg, entityName))
                        .collect(Collectors.toList());
                sb.append(String.join(", ", argStrings));
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private String extractEntityName(String fullClassName) {
        if (fullClassName == null || fullClassName.isBlank()) {
            return "Entity";
        }
        String simpleClassName = simpleNamePart(fullClassName);
        if (simpleClassName.endsWith("Service")) {
            return simpleClassName.substring(0, simpleClassName.length() - 7);
        }
        return simpleClassName;
    }

    private String descriptiveArgString(Object arg, String entityName) {
        if (arg == null) {
            return "null";
        }
        if (arg instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) arg;
            List<String> entries = map.entrySet().stream()
                    .limit(2)
                    .map(e -> e.getKey() + ": " + argToString(e.getValue()))
                    .collect(Collectors.toList());
            String mapContent = "{" + String.join(", ", entries) + (map.size() > 2 ? ", ..." : "") + "}";
            // Render as typed entity object, NOT a Map parameter
            return entityName + " " + mapContent;
        }
        if (arg instanceof Number) {
            return "Long(" + arg + ")";
        }
        if (arg instanceof String) {
            return "\"" + arg + "\"";
        }
        if (arg instanceof Boolean) {
            return "Boolean(" + arg + ")";
        }
        if (arg instanceof List) {
            List<?> list = (List<?>) arg;
            List<String> items = list.stream()
                    .limit(2)
                    .map(this::argToString)
                    .collect(Collectors.toList());
            return "List[" + String.join(", ", items) + (list.size() > 2 ? ", ..." : "") + "]";
        }
        return arg.toString();
    }

    private String argToString(Object arg) {
        if (arg == null) {
            return "null";
        }
        if (arg instanceof String) {
            return "\"" + arg + "\"";
        }
        if (arg instanceof Number || arg instanceof Boolean) {
            return arg.toString();
        }
        if (arg instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) arg;
            List<String> entries = map.entrySet().stream()
                    .limit(3)
                    .map(e -> e.getKey() + ": " + argToString(e.getValue()))
                    .collect(Collectors.toList());
            return "{" + String.join(", ", entries) + (map.size() > 3 ? ", ..." : "") + "}";
        }
        if (arg instanceof List) {
            List<?> list = (List<?>) arg;
            List<String> items = list.stream()
                    .limit(2)
                    .map(this::argToString)
                    .collect(Collectors.toList());
            return "[" + String.join(", ", items) + (list.size() > 2 ? ", ..." : "") + "]";
        }
        return arg.toString();
    }

    private String describeArgShape(Object arg) {
        if (arg == null) {
            return "Object";
        }
        if (arg instanceof String) {
            return "String";
        }
        if (arg instanceof Boolean) {
            return "Boolean";
        }
        if (arg instanceof Number) {
            return "Number";
        }
        if (arg instanceof List<?>) {
            return "List";
        }
        if (arg instanceof Map<?, ?>) {
            return "ObjectMap";
        }
        return arg.getClass().getSimpleName();
    }

    private String buildJsonArrayExample(String fullClassName) {
        String requested = (fullClassName == null || fullClassName.isBlank())
                ? "com.example.llmdyn.dynamic.service.MainService"
                : fullClassName.trim();
        String mainPackage = packagePart(requested);
        String mainSimpleName = simpleNamePart(requested);

        return "[\n" +
                "  {\"fullClassName\":\"" + requested + "\",\"sourceCode\":\"package " + mainPackage + ";\\n\\npublic class " + mainSimpleName + " {}\\n\"},\n" +
                "  {\"fullClassName\":\"com.example.llmdyn.dynamic.model.SampleEntity\",\"sourceCode\":\"package com.example.llmdyn.dynamic.model;\\n\\npublic class SampleEntity {}\\n\"}\n" +
                "]";
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private String packagePart(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot > 0 ? fullClassName.substring(0, lastDot) : "com.example.llmdyn.dynamic.service";
    }

    private String simpleNamePart(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 && lastDot < fullClassName.length() - 1
                ? fullClassName.substring(lastDot + 1)
                : "MainService";
    }

    private String extractJavaCode(String response) {
        response = response.trim();

        Pattern pattern = Pattern.compile("```(?:\\w+)?\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(response);

        String code;
        if (matcher.find()) {
            code = matcher.group(1).trim();
        } else {
            Pattern genericPattern = Pattern.compile("```[^\\n]*\\n(.*?)\\n```", Pattern.DOTALL);
            Matcher genericMatcher = genericPattern.matcher(response);

            if (genericMatcher.find()) {
                code = genericMatcher.group(1).trim();
            } else {
                code = response;
            }
        }

        // Remove separator lines added by LLMs
        code = code.replaceAll("(?m)^---.*---\\s*$", "");

        return code.trim();
    }

    private boolean isStructuredPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return false;
        }
        String trimmed = payload.trim();
        return trimmed.startsWith("[") || trimmed.startsWith("{");
    }

    private String stripAccessorPlaceholderHeaders(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            return sourceCode;
        }

        return sourceCode.lines()
                .filter(line -> {
                    String normalized = line.trim().toLowerCase();
                    return !normalized.equals("// getters and setters")
                            && !normalized.equals("// getters/setters")
                            && !normalized.equals("// accessors");
                })
                .collect(Collectors.joining(System.lineSeparator()))
                .trim();
    }

    private void validateExtractedJavaSource(String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalStateException("Generated output is empty after extraction.");
        }

        List<String> placeholderReasons = findPlaceholderReasons(sourceCode);
        if (!placeholderReasons.isEmpty()) {
            System.out.println("[GEN-VALIDATION] Placeholder/stub detected. Reasons: " + placeholderReasons);
            System.out.println("[GEN-VALIDATION] Problematic code excerpt:");
            System.out.println(extractProblemLines(sourceCode));
            throw new IllegalStateException(
                    "Generated output contains placeholders/stubs and is incomplete. Reasons: " + placeholderReasons
            );
        }

        List<String> invalidJpaNumericMappings = findInvalidJpaNumericMappings(sourceCode);
        if (!invalidJpaNumericMappings.isEmpty()) {
            System.out.println("[GEN-VALIDATION] Invalid JPA numeric mapping detected. Reasons: " + invalidJpaNumericMappings);
            System.out.println("[GEN-VALIDATION] Problematic code excerpt:");
            System.out.println(extractProblemLines(sourceCode));
            throw new IllegalStateException(
                    "Generated output contains invalid JPA numeric mapping. Reasons: " + invalidJpaNumericMappings
            );
        }

        List<String> accessorIssues = findMissingAccessorMethods(sourceCode);
        if (!accessorIssues.isEmpty()) {
            System.out.println("[GEN-VALIDATION] Missing accessor methods detected. Reasons: " + accessorIssues);
            System.out.println("[GEN-VALIDATION] Problematic code excerpt:");
            System.out.println(extractProblemLines(sourceCode));
            throw new IllegalStateException(
                    "Generated output is missing required getter/setter methods. Reasons: " + accessorIssues
            );
        }

        Pattern declaration = Pattern.compile("\\b(class|interface|enum)\\s+\\w+");
        if (!declaration.matcher(sourceCode).find()) {
            String preview = sourceCode.trim();
            if (preview.length() > 300) {
                preview = preview.substring(0, 300) + "...";
            }
            throw new IllegalStateException(
                    "Generated output does not contain a Java type declaration. Preview: " + preview
            );
        }
    }

    private List<String> findPlaceholderReasons(String sourceCode) {
        List<String> reasons = new ArrayList<>();
        String normalized = sourceCode.toLowerCase();
        if (normalized.contains("todo")) {
            reasons.add("token:todo");
        }

        Pattern ellipsisLine = Pattern.compile("(?m)^\\s*(//\\s*)?\\.\\.\\.\\s*$");
        if (ellipsisLine.matcher(sourceCode).find()) {
            reasons.add("line:ellipsis");
        }

        return reasons;
    }

    private List<String> findMissingAccessorMethods(String sourceCode) {
        List<String> issues = new ArrayList<>();

        if (sourceCode == null || sourceCode.isBlank() || isNonBeanLikeType(sourceCode)) {
            return issues;
        }

        CompilationUnitTree compilationUnit = parseCompilationUnit(sourceCode);
        if (compilationUnit == null) {
            return issues;
        }

        for (Tree typeDeclaration : compilationUnit.getTypeDecls()) {
            if (!(typeDeclaration instanceof ClassTree classTree) || classTree.getKind() != Tree.Kind.CLASS) {
                continue;
            }

            for (Tree member : classTree.getMembers()) {
                if (!(member instanceof VariableTree variableTree)) {
                    continue;
                }

                if (variableTree.getModifiers().getFlags().contains(Modifier.STATIC)) {
                    continue;
                }

                String fieldName = variableTree.getName().toString();
                String suffix = toAccessorSuffix(fieldName);
                boolean booleanField = isBooleanType(variableTree.getType().toString());

                if (!hasGetter(classTree, suffix, booleanField)) {
                    issues.add("missing getter for field:" + fieldName);
                }

                if (!variableTree.getModifiers().getFlags().contains(Modifier.FINAL) && !hasSetter(classTree, suffix)) {
                    issues.add("missing setter for field:" + fieldName);
                }
            }
        }

        return issues;
    }

    private boolean isNonBeanLikeType(String sourceCode) {
        CompilationUnitTree compilationUnit = parseCompilationUnit(sourceCode);
        if (compilationUnit == null) {
            return false;
        }

        for (Tree typeDeclaration : compilationUnit.getTypeDecls()) {
            if (!(typeDeclaration instanceof ClassTree classTree) || classTree.getKind() != Tree.Kind.CLASS) {
                return true;
            }
        }

        return false;
    }

    private CompilationUnitTree parseCompilationUnit(String sourceCode) {
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return null;
            }

            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                JavaFileObject sourceFile = new InMemoryJavaSource("Generated.java", sourceCode);
                JavacTask task = (JavacTask) compiler.getTask(null, fileManager, null, List.of("-proc:none"), null, List.of(sourceFile));
                Iterable<? extends CompilationUnitTree> parsedUnits = task.parse();
                Iterator<? extends CompilationUnitTree> iterator = parsedUnits.iterator();
                return iterator.hasNext() ? iterator.next() : null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean hasGetter(ClassTree classTree, String suffix, boolean booleanField) {
        for (Tree member : classTree.getMembers()) {
            if (!(member instanceof MethodTree methodTree)) {
                continue;
            }

            String methodName = methodTree.getName().toString();
            boolean standardGetter = methodName.equals("get" + suffix)
                    && methodTree.getParameters().isEmpty();
            boolean booleanGetter = booleanField
                    && methodName.equals("is" + suffix)
                    && methodTree.getParameters().isEmpty();

            if (standardGetter || booleanGetter) {
                return true;
            }
        }

        return false;
    }

    private boolean hasSetter(ClassTree classTree, String suffix) {
        for (Tree member : classTree.getMembers()) {
            if (!(member instanceof MethodTree methodTree)) {
                continue;
            }

            if (methodTree.getName().contentEquals("set" + suffix)
                    && methodTree.getParameters().size() == 1
                    && methodTree.getReturnType() != null
                    && "void".contentEquals(methodTree.getReturnType().toString())) {
                return true;
            }
        }

        return false;
    }

    private boolean isBooleanType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }

        String simpleTypeName = typeName.contains(".")
                ? typeName.substring(typeName.lastIndexOf('.') + 1)
                : typeName;
        return "boolean".equals(simpleTypeName) || "Boolean".equals(simpleTypeName);
    }

    private String toAccessorSuffix(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return fieldName;
        }
        if (fieldName.length() == 1) {
            return fieldName.toUpperCase();
        }
        return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
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

    private List<String> findInvalidJpaNumericMappings(String sourceCode) {
        List<String> reasons = new ArrayList<>();
        Pattern floatingScalePattern = Pattern.compile(
                "@Column\\s*\\((?s:[^)]*\\bscale\\b[^)]*)\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*private\\s+(Double|Float|double|float)\\s+(\\w+)\\s*;",
                Pattern.MULTILINE
        );
        Matcher matcher = floatingScalePattern.matcher(sourceCode);
        while (matcher.find()) {
            reasons.add("field:" + matcher.group(2) + " type:" + matcher.group(1) + " uses @Column(precision/scale)");
        }
        return reasons;
    }

    private String extractProblemLines(String sourceCode) {
        String[] lines = sourceCode.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            if (lower.contains("// repository")
                    || lower.contains("todo")
                    || (lower.contains("precision") && lower.contains("scale"))
                    || lower.matches("\\s*private\\s+(double|float)\\s+\\w+\\s*;")
                    || lower.matches("\\s*(//\\s*)?\\.\\.\\.\\s*")) {
                sb.append(String.format("%4d | %s%n", i + 1, lines[i]));
            }
        }

        if (sb.isEmpty()) {
            for (int i = 0; i < Math.min(lines.length, 20); i++) {
                sb.append(String.format("%4d | %s%n", i + 1, lines[i]));
            }
        }
        return sb.toString();
    }
}