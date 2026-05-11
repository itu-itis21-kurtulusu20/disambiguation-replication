package com.example.llmdyn.service;

import com.example.llmdyn.model.FailureDetail;
import com.example.llmdyn.model.PromptProfile;
import com.example.llmdyn.model.TestCase;
import com.example.llmdyn.runtime.BeanRegistrar;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class TestCaseExecutorService {

    private final DynamicTestExecutionService testExecutionService;
    private final CodeGenerationService codeGenerationService;
    private final FailureAnalysisService failureAnalysisService;
    private final EntityManager entityManager;
    private final Path testCasesDir;
    private final DatabaseCleanupService databaseCleanupService;
    private final BeanRegistrar beanRegistrar;
    private final boolean clearBeforeEachTestCase;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public TestCaseExecutorService(DynamicTestExecutionService testExecutionService,
                                   CodeGenerationService codeGenerationService,
                                   FailureAnalysisService failureAnalysisService,
                                   EntityManager entityManager,
                                   DatabaseCleanupService databaseCleanupService,
                                   BeanRegistrar beanRegistrar,
                                   @Value("${testcases.dir:src/main/resources/test-cases}") String testCasesDir,
                                   @Value("${testcases.cleanup.clear-before-each-case:false}") boolean clearBeforeEachTestCase) {
        this.testExecutionService = testExecutionService;
        this.codeGenerationService = codeGenerationService;
        this.failureAnalysisService = failureAnalysisService;
        this.entityManager = entityManager;
        this.databaseCleanupService = databaseCleanupService;
        this.beanRegistrar = beanRegistrar;
        this.testCasesDir = Path.of(testCasesDir);
        this.clearBeforeEachTestCase = clearBeforeEachTestCase;
    }

    public List<TestCaseResult> executeAllTestCases() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:test-cases/*.yaml");

        List<TestCaseResult> results = new ArrayList<>();
        Yaml yaml = new Yaml();

        for (Resource resource : resources) {
            try (InputStream is = resource.getInputStream()) {
                TestCase testCase = yaml.loadAs(is, TestCase.class);
                TestCaseResult result = executeTestCase(testCase);
                results.add(result);
            }
        }

        return results;
    }

    public TestCaseResult executeTestCase(String fileName) throws Exception {
        return executeTestCase(fileName, PromptProfile.ENHANCED, true);
    }

    public TestCaseResult executeTestCase(String fileName,
                                          PromptProfile promptProfile,
                                          boolean includeProjectContext) throws Exception {

        Path testCasePath = testCasesDir.resolve(fileName);
        return executeTestCase(testCasePath, promptProfile, includeProjectContext, null);
    }

    public TestCaseResult executeTestCase(Path testCasePath,
                                          PromptProfile promptProfile,
                                          boolean includeProjectContext) throws Exception {
        return executeTestCase(testCasePath, promptProfile, includeProjectContext, null);
    }

    public TestCaseResult executeTestCase(Path testCasePath,
                                          PromptProfile promptProfile,
                                          boolean includeProjectContext,
                                          Boolean includeJpaGuidelines) throws Exception {

        // Optional per-test isolation. Batch run-all can disable this and reset once before starting.
        if (clearBeforeEachTestCase) {
            databaseCleanupService.clearAllTables();
        }

        if (!Files.exists(testCasePath)) {
            throw new IllegalArgumentException("Test case file not found: " + testCasePath);
        }

        Yaml yaml = new Yaml();
        try (InputStream is = Files.newInputStream(testCasePath)) {
            TestCase testCase = yaml.loadAs(is, TestCase.class);
            String runLabel = removeYamlSuffix(testCasePath.getFileName().toString());
            return executeTestCase(testCase, promptProfile, includeProjectContext, runLabel, includeJpaGuidelines);
        }
    }

    @Transactional(transactionManager = "transactionManager")
    public TestCaseResult executeTestCase(TestCase testCase) {
        return executeTestCase(testCase, PromptProfile.ENHANCED, true);
    }

    @Transactional(transactionManager = "transactionManager")
    public TestCaseResult executeTestCase(TestCase testCase,
                                          PromptProfile promptProfile,
                                          boolean includeProjectContext) {
        return executeTestCase(testCase, promptProfile, includeProjectContext, deriveRunLabel(testCase), null);
    }

    @Transactional(transactionManager = "transactionManager")
    public TestCaseResult executeTestCase(TestCase testCase,
                                          PromptProfile promptProfile,
                                          boolean includeProjectContext,
                                          String runLabel) {
        return executeTestCase(testCase, promptProfile, includeProjectContext, runLabel, null);
    }

    @Transactional(transactionManager = "transactionManager")
    public TestCaseResult executeTestCase(TestCase testCase,
                                          PromptProfile promptProfile,
                                          boolean includeProjectContext,
                                          String runLabel,
                                          Boolean includeJpaGuidelines) {
        TestCaseResult result = new TestCaseResult(testCase.getFullClassName(), testCase.getPrompt());
        String generatedSourceSnapshot = null;
        codeGenerationService.clearLastGeneratedSource();

        try {

            String compilationResult = codeGenerationService.generateAndCompile(
                    testCase.getFullClassName(),
                    testCase.getPrompt(),
                    testCase.getTests(),
                    promptProfile,
                    includeProjectContext,
                    runLabel,
                    includeJpaGuidelines
            );
            generatedSourceSnapshot = codeGenerationService.getLastGeneratedSource();

            if (!"SUCCESS".equals(compilationResult)) {
                FailureDetail failureDetail = ErrorClassifier.classify(
                        "compilation",
                        null,
                        "Compilation failed: " + compilationResult
                );
                String llmNotes = failureAnalysisService.analyzeFailure(
                        "compilation",
                        testCase.getPrompt(),
                        testCase.getFullClassName(),
                        generatedSourceSnapshot,
                        "Compilation failed: " + compilationResult,
                        null,
                        failureDetail
                );
                result.addError("compilation", "Compilation failed: " + compilationResult, null, failureDetail, llmNotes);
                return result;
            }

            // Rebuild EMF once more right before execution so newly generated entities are mapped
            // and their tables are materialized after run-all startup cleanup.
            beanRegistrar.recreateEntityManagerFactory();

            for (TestCase.TestScenario scenario : testCase.getTests()) {
                try {
                    String beanName = extractBeanName(testCase.getFullClassName());
                    Object[] args = scenario.getArgs() != null ? scenario.getArgs().toArray() : new Object[0];

                    System.out.println("Executing: " + scenario.getMethodName() +
                            " with args: " + Arrays.toString(args) +
                            " (types: " + Arrays.stream(args)
                            .map(a -> a == null ? "null" : a.getClass().getSimpleName())
                            .collect(Collectors.joining(", ")) + ")");

                    DynamicTestExecutionService.TestResult testResult = testExecutionService.executeTest(
                            beanName,
                            scenario.getMethodName(),
                            args,
                            scenario.getExpectedResult()
                    );

                    System.out.println("Result - Success: " + testResult.isSuccess() +
                            ", Actual: " + testResult.getActualResult() +
                            ", Expected: " + testResult.getExpectedResult());

                    if (testResult.isSuccess()) {
                        result.addScenarioResult(scenario.getMethodName(), testResult);
                    } else {
                        FailureDetail failureDetail = ErrorClassifier.functionalFailure(
                                "execution",
                                "Assertion mismatch in method '" + scenario.getMethodName() + "'"
                        );
                        String assertionContext = buildAssertionMismatchMessage(scenario, args, testResult);
                        String llmNotes = failureAnalysisService.analyzeFailure(
                                "execution",
                                testCase.getPrompt(),
                                testCase.getFullClassName(),
                                generatedSourceSnapshot,
                                assertionContext,
                                null,
                                failureDetail
                        );
                        result.addScenarioResult(scenario.getMethodName(), testResult, failureDetail, llmNotes);
                    }

                } catch (Exception e) {
                    Throwable analysisThrowable = unwrapForAnalysis(e);
                    String failureMessage = buildFailureMessage(e, analysisThrowable);
                    System.err.println("Test failed: " + scenario.getMethodName() + " - " + failureMessage);
                    e.printStackTrace();
                    FailureDetail failureDetail = ErrorClassifier.classify("execution", analysisThrowable, failureMessage);
                    String llmNotes = failureAnalysisService.analyzeFailure(
                            "execution",
                            testCase.getPrompt(),
                            testCase.getFullClassName(),
                            generatedSourceSnapshot,
                            failureMessage,
                            analysisThrowable,
                            failureDetail
                    );
                    result.addError(scenario.getMethodName(), failureMessage, analysisThrowable, failureDetail, llmNotes);
                }
            }
        } catch (Exception e) {
            if (generatedSourceSnapshot == null) {
                generatedSourceSnapshot = codeGenerationService.getLastGeneratedSource();
            }
            FailureDetail failureDetail = ErrorClassifier.classify("generation", e, e.getMessage());
            String stage = "generation";
            String prefix = "Generation/extraction failed: ";

            if (failureDetail.getLevel() == com.example.llmdyn.model.FailureLevel.LEVEL_2_COMPILATION) {
                stage = "compilation";
                prefix = "Compilation failed: ";
            }

            String errorMessage = prefix + e.getMessage();
            String llmNotes = failureAnalysisService.analyzeFailure(
                    stage,
                    testCase.getPrompt(),
                    testCase.getFullClassName(),
                    generatedSourceSnapshot,
                    errorMessage,
                    e,
                    failureDetail
            );
            if ("compilation".equals(stage) && testCase.getTests() != null && !testCase.getTests().isEmpty()) {
                for (TestCase.TestScenario scenario : testCase.getTests()) {
                    String methodName = scenario.getMethodName() != null ? scenario.getMethodName() : "compilation";
                    result.addError(methodName, errorMessage, e, failureDetail, llmNotes);
                }
            } else {
                result.addError(stage, errorMessage, e, failureDetail, llmNotes);
            }
            e.printStackTrace();
        }

        return result;
    }

    private String deriveRunLabel(TestCase testCase) {
        if (testCase == null || testCase.getFullClassName() == null || testCase.getFullClassName().isBlank()) {
            return "test-case";
        }
        String fullClassName = testCase.getFullClassName().trim();
        int idx = fullClassName.lastIndexOf('.');
        return idx >= 0 ? fullClassName.substring(idx + 1) : fullClassName;
    }

    private String removeYamlSuffix(String fileName) {
        if (fileName == null) {
            return "test-case";
        }
        return fileName.replaceFirst("\\.ya?ml$", "");
    }



    private String extractBeanName(String fullClassName) {
        // Extract simple class name (e.g., BookService from com.example.llmdyn.dynamic.service.BookService)
        String simpleClassName = fullClassName.substring(fullClassName.lastIndexOf('.') + 1);

        // Convert to camelCase (BookService -> bookService)
        return Character.toLowerCase(simpleClassName.charAt(0)) + simpleClassName.substring(1);
    }

    private String buildAssertionMismatchMessage(TestCase.TestScenario scenario,
                                                 Object[] args,
                                                 DynamicTestExecutionService.TestResult testResult) {
        return "Assertion mismatch in method '" + scenario.getMethodName() + "'. "
                + "args=" + toJsonSafe(args)
                + "; expected=" + toJsonSafe(testResult.getExpectedResult())
                + "; actual=" + toJsonSafe(testResult.getActualResult());
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private Throwable unwrapForAnalysis(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation && invocation.getTargetException() != null) {
            return invocation.getTargetException();
        }
        return throwable;
    }

    private String buildFailureMessage(Throwable original, Throwable analysisThrowable) {
        StringBuilder sb = new StringBuilder();
        if (analysisThrowable != null) {
            sb.append(analysisThrowable.getClass().getSimpleName());
            if (analysisThrowable.getMessage() != null && !analysisThrowable.getMessage().isBlank()) {
                sb.append(": ").append(analysisThrowable.getMessage());
            }
            Throwable root = analysisThrowable;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            if (root != analysisThrowable) {
                sb.append(" | rootCause=").append(root.getClass().getSimpleName());
                if (root.getMessage() != null && !root.getMessage().isBlank()) {
                    sb.append(": ").append(root.getMessage());
                }
            }
        }
        if (sb.length() == 0 && original != null) {
            sb.append(original.getClass().getSimpleName());
            if (original.getMessage() != null && !original.getMessage().isBlank()) {
                sb.append(": ").append(original.getMessage());
            }
        }
        return sb.length() == 0 ? "Unknown execution failure" : sb.toString();
    }

    public static class TestCaseResult {
        private final String className;
        private final String prompt;
        private final List<ScenarioResult> scenarioResults = new ArrayList<>();
        private FailureDetail firstFailure;
        private int passed = 0;
        private int failed = 0;

        public TestCaseResult(String className, String prompt) {
            this.className = className;
            this.prompt = prompt;
        }

        public void addScenarioResult(String methodName, DynamicTestExecutionService.TestResult testResult) {
            scenarioResults.add(new ScenarioResult(methodName, testResult, null, null, null, null));
            if (testResult.isSuccess()) {
                passed++;
            } else {
                failed++;
            }
        }

        public void addScenarioResult(String methodName,
                                      DynamicTestExecutionService.TestResult testResult,
                                      FailureDetail failureDetail,
                                      String llmNotes) {
            scenarioResults.add(new ScenarioResult(methodName, testResult, null, null, failureDetail, llmNotes));
            if (testResult.isSuccess()) {
                passed++;
            } else {
                failed++;
                captureFirstFailure(failureDetail);
            }
        }

        public void addError(String methodName, String error) {
            addError(methodName, error, null, null, null);
        }

        public void addError(String methodName, String error, Throwable exception, FailureDetail failureDetail) {
            addError(methodName, error, exception, failureDetail, null);
        }

        public void addError(String methodName,
                             String error,
                             Throwable exception,
                             FailureDetail failureDetail,
                             String llmNotes) {
            scenarioResults.add(new ScenarioResult(methodName, null, error, exception, failureDetail, llmNotes));
            captureFirstFailure(failureDetail);
            failed++;
        }

        public void captureFirstFailure(FailureDetail failureDetail) {
            if (this.firstFailure == null && failureDetail != null) {
                this.firstFailure = failureDetail;
            }
        }

        public String getClassName() { return className; }
        public String getPrompt() { return prompt; }
        public List<ScenarioResult> getScenarioResults() { return scenarioResults; }
        public int getPassed() { return passed; }
        public int getFailed() { return failed; }
        public boolean isAllPassed() { return failed == 0; }
        public FailureDetail getFirstFailure() { return firstFailure; }
    }

    public static class ScenarioResult {
        private final String methodName;
        private final DynamicTestExecutionService.TestResult testResult;
        private final String error;
        private final Throwable exception ;
        private final FailureDetail failureDetail;
        private final String llmNotes;

        public ScenarioResult(String methodName,
                              DynamicTestExecutionService.TestResult testResult,
                              String error,
                              Throwable exception,
                              FailureDetail failureDetail,
                              String llmNotes) {
            this.methodName = methodName;
            this.testResult = testResult;
            this.error = error;
            this.exception = exception;
            this.failureDetail = failureDetail;
            this.llmNotes = llmNotes;
        }

        public String getMethodName() { return methodName; }
        public DynamicTestExecutionService.TestResult getTestResult() { return testResult; }
        public String getError() { return error; }
        public boolean isSuccess() { return error == null && testResult != null && testResult.isSuccess(); }
        public FailureDetail getFailureDetail() { return failureDetail; }
        public String getLlmNotes() { return llmNotes; }

        public Throwable getException() { // This method must exist
            return exception;
        }
    }
}