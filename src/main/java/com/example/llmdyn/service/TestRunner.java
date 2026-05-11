package com.example.llmdyn.service;

import com.example.llmdyn.model.ErrorCode;
import com.example.llmdyn.model.FailureDetail;
import com.example.llmdyn.model.FailureLevel;
import com.example.llmdyn.model.PromptProfile;
import com.example.llmdyn.runtime.BeanRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TestRunner {

    private final TestCaseExecutorService testCaseExecutorService;
    private final BeanRegistrar beanRegistrar;
    private final DatabaseCleanupService databaseCleanupService;
    private final Path testCasesRootDir;
    private final boolean dropAllTablesBeforeRunAll;

    public TestRunner(
            TestCaseExecutorService testCaseExecutorService,
            BeanRegistrar beanRegistrar,
            DatabaseCleanupService databaseCleanupService,
            @Value("${testcases.root-dir:${testcases.dir:src/main/resources/test-cases}}") String testCasesRootDir,
            @Value("${testcases.cleanup.drop-all-before-run-all:true}") boolean dropAllTablesBeforeRunAll
    ) {
        this.testCaseExecutorService = testCaseExecutorService;
        this.beanRegistrar = beanRegistrar;
        this.databaseCleanupService = databaseCleanupService;
        this.testCasesRootDir = Path.of(testCasesRootDir);
        this.dropAllTablesBeforeRunAll = dropAllTablesBeforeRunAll;
    }

    public Map<String, Object> runAllTests() {
        return runAllTests(PromptProfile.ENHANCED, true, null, null);
    }

    public Map<String, Object> runAllTests(PromptProfile promptProfile, boolean includeProjectContext) {
        return runAllTests(promptProfile, includeProjectContext, null, null);
    }

    public Map<String, Object> runAllTests(PromptProfile promptProfile,
                                           boolean includeProjectContext,
                                           String group) {
        return runAllTests(promptProfile, includeProjectContext, group, null);
    }

    public Map<String, Object> runAllTests(PromptProfile promptProfile,
                                           boolean includeProjectContext,
                                           String group,
                                           Boolean includeJpaGuidelines) {
        PromptProfile effectiveProfile = promptProfile == null ? PromptProfile.ENHANCED : promptProfile;
        String selectedGroup = normalizeGroup(group);
        Path resolvedTestDir = resolveTestCasesDir(selectedGroup);
        String selectedGroupValue = selectedGroup == null ? "default" : selectedGroup;

        System.out.println("\n╔════════════════════════════════════════════════════╗");
        System.out.println("║       STARTING BATCH TEST EXECUTION                ║");
        System.out.println("╚════════════════════════════════════════════════════╝\n");
        System.out.println("Prompt profile: " + effectiveProfile + ", includeProjectContext=" + includeProjectContext);
        System.out.println("Include JPA guidelines: " + (includeJpaGuidelines == null ? "default" : includeJpaGuidelines));
        System.out.println("Selected test group: " + selectedGroupValue + ", directory=" + resolvedTestDir);

        // One-time reset at the beginning of batch execution.
        if (dropAllTablesBeforeRunAll) {
            databaseCleanupService.dropAllPublicTables();
        } else {
            truncateTablesFromTestCases(resolvedTestDir);
        }

        File testDir = resolvedTestDir.toFile();
        File[] testFiles = testDir.listFiles((dir, name) -> name.endsWith(".yaml"));
        if (testFiles != null) {
            Arrays.sort(testFiles, Comparator.comparing(File::getName));
        }

        if (testFiles == null || testFiles.length == 0) {
            System.out.println("❌ No test files found in " + testDir.getAbsolutePath());
            return Map.of(
                    "error", "No test files found",
                    "totalTests", 0,
                    "selectedGroup", selectedGroupValue,
                    "resolvedTestCasesDir", resolvedTestDir.toString()
            );
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int totalPassed = 0;
        int totalFailed = 0;
        Map<FailureLevel, Integer> firstFailureCounts = initializeFailureCounts();
        Map<ErrorCode, Integer> firstFailureErrorCodeCounts = initializeErrorCodeCounts();
        int firstFailureDenominator = 0;

        for (File testFile : testFiles) {
            String fileName = testFile.getName();
            System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("📄 Executing: " + fileName);
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            try {
                // 1. Execute test case (which should generate, compile, and register dynamic entities)
                TestCaseExecutorService.TestCaseResult result =
                        testCaseExecutorService.executeTestCase(
                                testFile.toPath(),
                                effectiveProfile,
                                includeProjectContext,
                                includeJpaGuidelines
                        );

                // 2. Clear entities and recreate EntityManagerFactory AFTER registration
                beanRegistrar.clearEntities();
                beanRegistrar.recreateEntityManagerFactory();

                int passed = result.getPassed();
                int failed = result.getFailed();
                FailureDetail firstFailure = result.getFirstFailure();

                totalPassed += passed;
                totalFailed += failed;

                if (failed > 0) {
                    firstFailureDenominator++;
                    FailureLevel level = firstFailure != null ? firstFailure.getLevel() : FailureLevel.UNCLASSIFIED;
                    ErrorCode errorCode = firstFailure != null ? firstFailure.getErrorCode() : ErrorCode.UNCLASSIFIED;
                    firstFailureCounts.merge(level, 1, Integer::sum);
                    firstFailureErrorCodeCounts.merge(errorCode, 1, Integer::sum);
                }

                String status = failed == 0 ? "✅ PASSED" : "❌ FAILED";
                System.out.printf("\n%s - %d/%d tests passed\n", status, passed, passed + failed);
                if (failed > 0 && firstFailure != null) {
                    System.out.printf("  First failure: %s (%s)\n",
                            firstFailure.getErrorCode().name(),
                            firstFailure.getCategory());
                }

                if (failed > 0) {
                    System.out.println("\n  Failed tests:");
                    List<TestCaseExecutorService.ScenarioResult> scenarios = result.getScenarioResults();

                    if (scenarios != null) {
                        for (TestCaseExecutorService.ScenarioResult scenario : scenarios) {
                            if (!scenario.isSuccess()) {
                                System.out.printf("    ❌ %s - %s%n",
                                        scenario.getMethodName(),
                                        scenario.getError() != null ? scenario.getError() : "Comparison failed");

                                // Log exception if available
                                if (scenario.getException() != null) {
                                    System.out.println("       Exception: " + scenario.getException().getClass().getSimpleName());
                                    System.out.println("       Message: " + scenario.getException().getMessage());
                                    if (scenario.getException().getCause() != null) {
                                        System.out.println("       Cause: " + scenario.getException().getCause().getMessage());
                                    }
                                    System.out.println("       Stack trace:");
                                    printStackTraceIndented(scenario.getException(), "       ");
                                }

                                if (scenario.getLlmNotes() != null && !scenario.getLlmNotes().isBlank()) {
                                    System.out.println("       LLM analysis:");
                                    System.out.println("       " + scenario.getLlmNotes().replace("\n", "\n       "));
                                }
                            }
                        }
                    }
                }

                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("fileName", fileName);
                resultMap.put("passed", passed);
                resultMap.put("failed", failed);
                resultMap.put("scenarioResults", result.getScenarioResults() != null ? result.getScenarioResults() : List.of());
                resultMap.put("firstFailure", firstFailure);
                resultMap.put("group", selectedGroupValue);
                results.add(resultMap);

            } catch (Exception e) {
                System.out.println("❌ Failed to execute " + fileName + ": " + e.getMessage());
                printStackTraceIndented(e, "   ");
                FailureDetail failureDetail = ErrorClassifier.classify("runner", e, e.getMessage());
                firstFailureDenominator++;
                firstFailureCounts.merge(failureDetail.getLevel(), 1, Integer::sum);
                firstFailureErrorCodeCounts.merge(failureDetail.getErrorCode(), 1, Integer::sum);

                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("fileName", fileName);
                resultMap.put("passed", 0);
                resultMap.put("failed", 1);
                resultMap.put("error", e.getMessage());
                resultMap.put("firstFailure", failureDetail);
                resultMap.put("group", selectedGroupValue);
                results.add(resultMap);
                totalFailed++;
            }
        }

        Map<String, Object> table2 = buildTable2(firstFailureCounts, firstFailureDenominator);
        List<Map<String, Object>> firstFailureErrorCodeDistribution =
                buildFirstFailureErrorCodeDistribution(firstFailureErrorCodeCounts, firstFailureDenominator);

        printFinalReport(results, totalPassed, totalFailed);
        printTable2(table2);
        printErrorCodeDistribution(firstFailureErrorCodeDistribution);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("generationOptions", Map.of(
                "promptProfile", effectiveProfile.name(),
                "includeProjectContext", includeProjectContext,
                "group", selectedGroupValue,
                "includeJpaGuidelines", includeJpaGuidelines == null ? "default" : includeJpaGuidelines
        ));
        response.put("selectedGroup", selectedGroupValue);
        response.put("resolvedTestCasesDir", resolvedTestDir.toString());
        response.put("totalTests", totalPassed + totalFailed);
        response.put("totalPassed", totalPassed);
        response.put("totalFailed", totalFailed);
        response.put("results", results);
        response.put("firstFailureDenominator", firstFailureDenominator);
        response.put("firstFailureDistribution", buildFirstFailureDistribution(firstFailureCounts, firstFailureDenominator));
        response.put("firstFailureErrorCodeDistribution", firstFailureErrorCodeDistribution);
        response.put("table2", table2);
        return response;
    }

    private String normalizeGroup(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        String value = group.trim();
        if (!value.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Invalid group: " + group + ". Allowed pattern: [a-z0-9-]+");
        }
        return value;
    }

    private Path resolveTestCasesDir(String normalizedGroup) {
        Path root = testCasesRootDir.toAbsolutePath().normalize();
        Path resolved = normalizedGroup == null ? root : root.resolve(normalizedGroup).normalize();

        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid group path");
        }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Test case group directory not found: " + resolved);
        }
        return resolved;
    }

    private Map<ErrorCode, Integer> initializeErrorCodeCounts() {
        Map<ErrorCode, Integer> counts = new EnumMap<>(ErrorCode.class);
        for (ErrorCode errorCode : ErrorCode.values()) {
            counts.put(errorCode, 0);
        }
        return counts;
    }

    private Map<FailureLevel, Integer> initializeFailureCounts() {
        Map<FailureLevel, Integer> counts = new EnumMap<>(FailureLevel.class);
        counts.put(FailureLevel.LEVEL_1_GENERATION_EXTRACTION, 0);
        counts.put(FailureLevel.LEVEL_2_COMPILATION, 0);
        counts.put(FailureLevel.LEVEL_3_SPRING_CONTEXT_INIT, 0);
        counts.put(FailureLevel.LEVEL_4_PERSISTENCE_DATABASE, 0);
        counts.put(FailureLevel.LEVEL_5_FUNCTIONAL_MERGED, 0);
        counts.put(FailureLevel.UNCLASSIFIED, 0);
        return counts;
    }

    private List<Map<String, Object>> buildFirstFailureDistribution(Map<FailureLevel, Integer> counts, int denominator) {
        List<FailureLevel> order = List.of(
                FailureLevel.LEVEL_1_GENERATION_EXTRACTION,
                FailureLevel.LEVEL_2_COMPILATION,
                FailureLevel.LEVEL_3_SPRING_CONTEXT_INIT,
                FailureLevel.LEVEL_4_PERSISTENCE_DATABASE,
                FailureLevel.LEVEL_5_FUNCTIONAL_MERGED,
                FailureLevel.UNCLASSIFIED
        );

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (FailureLevel level : order) {
            int frequency = counts.getOrDefault(level, 0);
            double percentage = denominator == 0 ? 0.0 : (frequency * 100.0) / denominator;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level.name());
            row.put("category", level.getLabel());
            row.put("frequency", frequency);
            row.put("percentage", Math.round(percentage * 100.0) / 100.0);
            distribution.add(row);
        }
        return distribution;
    }

    private List<Map<String, Object>> buildFirstFailureErrorCodeDistribution(Map<ErrorCode, Integer> counts, int denominator) {
        List<Map<String, Object>> distribution = new ArrayList<>();
        for (ErrorCode errorCode : ErrorCode.values()) {
            int frequency = counts.getOrDefault(errorCode, 0);
            double percentage = denominator == 0 ? 0.0 : (frequency * 100.0) / denominator;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("errorCode", errorCode.name());
            row.put("category", errorCode.getLevel().getLabel());
            row.put("frequency", frequency);
            row.put("percentage", Math.round(percentage * 100.0) / 100.0);
            distribution.add(row);
        }
        return distribution;
    }

    private Map<String, Object> buildTable2(Map<FailureLevel, Integer> counts, int denominator) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<FailureLevel> orderedLevels = List.of(
                FailureLevel.LEVEL_1_GENERATION_EXTRACTION,
                FailureLevel.LEVEL_2_COMPILATION,
                FailureLevel.LEVEL_3_SPRING_CONTEXT_INIT,
                FailureLevel.LEVEL_4_PERSISTENCE_DATABASE,
                FailureLevel.LEVEL_5_FUNCTIONAL_MERGED
        );

        for (FailureLevel level : orderedLevels) {
            int frequency = counts.getOrDefault(level, 0);
            double percentage = denominator == 0 ? 0.0 : (frequency * 100.0) / denominator;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("errorCategory", level.getLabel());
            row.put("frequency", frequency);
            row.put("percentage", Math.round(percentage * 100.0) / 100.0);
            rows.add(row);
        }

        Map<String, Object> table2 = new LinkedHashMap<>();
        table2.put("title", "TABLE 2. DISTRIBUTION OF FIRST FAILURES IN GENERATED SPRING BOOT APPLICATIONS");
        table2.put("sampleSize", denominator);
        table2.put("rows", rows);
        table2.put("unclassifiedFrequency", counts.getOrDefault(FailureLevel.UNCLASSIFIED, 0));
        return table2;
    }

    @SuppressWarnings("unchecked")
    private void printTable2(Map<String, Object> table2) {
        System.out.println("\nTABLE 2. DISTRIBUTION OF FIRST FAILURES IN GENERATED SPRING BOOT APPLICATIONS");
        System.out.println("N = " + table2.getOrDefault("sampleSize", 0));
        System.out.println("--------------------------------------------------------------------------");
        System.out.printf("%-36s %10s %12s%n", "Error Category", "Frequency", "Percentage");
        System.out.println("--------------------------------------------------------------------------");

        List<Map<String, Object>> rows = (List<Map<String, Object>>) table2.getOrDefault("rows", List.of());
        for (Map<String, Object> row : rows) {
            String category = String.valueOf(row.getOrDefault("errorCategory", ""));
            int frequency = ((Number) row.getOrDefault("frequency", 0)).intValue();
            double percentage = ((Number) row.getOrDefault("percentage", 0.0)).doubleValue();
            System.out.printf("%-36s %10d %11.2f%%%n", category, frequency, percentage);
        }

        System.out.println("--------------------------------------------------------------------------");
        System.out.println("Unclassified: " + table2.getOrDefault("unclassifiedFrequency", 0));
    }

    private void printErrorCodeDistribution(List<Map<String, Object>> rows) {
        System.out.println("\nFIRST-FAILURE SUBTYPE DISTRIBUTION (ERROR CODES)");
        System.out.println("--------------------------------------------------------------------------------------");
        System.out.printf("%-34s %-24s %8s %10s%n", "Error Code", "Category", "Frequency", "Percentage");
        System.out.println("--------------------------------------------------------------------------------------");

        for (Map<String, Object> row : rows) {
            int frequency = ((Number) row.getOrDefault("frequency", 0)).intValue();
            if (frequency <= 0) {
                continue;
            }
            String errorCode = String.valueOf(row.getOrDefault("errorCode", ""));
            String category = String.valueOf(row.getOrDefault("category", ""));
            double percentage = ((Number) row.getOrDefault("percentage", 0.0)).doubleValue();
            System.out.printf("%-34s %-24s %8d %9.2f%%%n", errorCode, category, frequency, percentage);
        }
    }

    private void truncateTablesFromTestCases(Path testCasesDir) {
        File testDir = testCasesDir.toFile();
        File[] testFiles = testDir.listFiles((dir, name) -> name.endsWith(".yaml"));
        Set<String> tableNames = new HashSet<>();
        Yaml yaml = new Yaml();
        Pattern pattern = Pattern.compile("table called \\\"(\\w+)\\\"");

        if (testFiles == null) return;

        for (File testFile : testFiles) {
            try (FileInputStream fis = new FileInputStream(testFile)) {
                Map<String, Object> data = yaml.load(fis);
                String prompt = (String) data.get("prompt");
                if (prompt != null) {
                    Matcher m = pattern.matcher(prompt);
                    if (m.find()) {
                        tableNames.add(m.group(1));
                    }
                }
            } catch (Exception e) {
                System.out.println("Failed to parse " + testFile.getName() + ": " + e.getMessage());
            }
        }

        for (String table : tableNames) {
            try {
                databaseCleanupService.truncateTable(table);
                System.out.println("Truncated table: " + table);
            } catch (Exception e) {
                System.out.println("Failed to truncate table " + table + ": " + e.getMessage());
            }
        }
    }

    private void printStackTraceIndented(Throwable throwable, String indent) {
        if (throwable == null) {
            return;
        }
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String formatted = sw.toString().replace("\n", "\n" + indent).trim();
        System.out.println(indent + formatted);
    }

    private void printFinalReport(List<Map<String, Object>> results, int totalPassed, int totalFailed) {
        System.out.println("\n\n╔════════════════════════════════════════════════════╗");
        System.out.println("║          FINAL TEST EXECUTION REPORT               ║");
        System.out.println("╚════════════════════════════════════════════════════╝\n");

        for (Map<String, Object> result : results) {
            String fileName = (String) result.get("fileName");
            int passed = (int) result.getOrDefault("passed", 0);
            int failed = (int) result.getOrDefault("failed", 0);
            String status = failed == 0 ? "✅" : "❌";

            System.out.printf("%s %s - %d/%d passed%n", status, fileName, passed, passed + failed);

            if (failed > 0) {
                Object rawScenarios = result.get("scenarioResults");
                if (rawScenarios instanceof List<?> scenarios) {
                    for (Object item : scenarios) {
                        if (item instanceof TestCaseExecutorService.ScenarioResult scenario
                                && scenario.getLlmNotes() != null
                                && !scenario.getLlmNotes().isBlank()) {
                            System.out.println("   LLM note: " + firstLine(scenario.getLlmNotes()));
                            break;
                        }
                    }
                }
            }
        }

        System.out.println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.printf("Total Tests: %d | Passed: %d | Failed: %d%n",
                totalPassed + totalFailed, totalPassed, totalFailed);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (totalFailed == 0) {
            System.out.println("🎉 ALL TESTS PASSED!");
        } else {
            System.out.println("⚠️  SOME TESTS FAILED - CHECK LOGS ABOVE");
        }
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "N/A";
        }
        int idx = text.indexOf('\n');
        String line = idx >= 0 ? text.substring(0, idx) : text;
        return line.length() > 180 ? line.substring(0, 180) + "..." : line;
    }
}