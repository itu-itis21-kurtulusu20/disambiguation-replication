package com.example.llmdyn.controller;

import com.example.llmdyn.llm.LLMServiceFactory;
import com.example.llmdyn.runtime.DynamicCodeExecutor;
import com.example.llmdyn.service.CodeGenerationService;
import com.example.llmdyn.service.DynamicTestExecutionService;
import com.example.llmdyn.service.ProjectContextService;
import com.example.llmdyn.service.TestCaseExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/experiments")

public class ExperimentController {

    private final LLMServiceFactory llmServiceFactory;
    private final DynamicCodeExecutor dynamicCodeExecutor;
    private final ProjectContextService projectContextService;
    private final DynamicTestExecutionService dynamicTestExecutionService;
    private final TestCaseExecutorService testCaseExecutorService;
    private final CodeGenerationService codeGenerationService;


    @Value("${dynamic.compile.output:./generated-classes}")
    private String compileOutput;

    public ExperimentController(LLMServiceFactory llmServiceFactory, DynamicCodeExecutor dynamicCodeExecutor, ProjectContextService projectContextService, DynamicTestExecutionService dynamicTestExecutionService, TestCaseExecutorService testCaseExecutorService, CodeGenerationService codeGenerationService) {
        this.llmServiceFactory = llmServiceFactory;
        this.dynamicCodeExecutor = dynamicCodeExecutor;
        this.projectContextService = projectContextService;
        this.dynamicTestExecutionService = dynamicTestExecutionService;
        this.testCaseExecutorService = testCaseExecutorService;
        this.codeGenerationService = codeGenerationService;
    }


   /* @PostMapping("/run")
    public ResponseEntity<?> runExperiment(@RequestBody Map<String, String> body) throws Exception {
        String fullClassName = body.get("fullClassName");
        String prompt = body.get("prompt");

        String result = codeGenerationService.generateAndCompile(fullClassName, prompt, testCase.getTests());
        return ResponseEntity.ok(Map.of("result", result));
    }*/


    @PostMapping("/test")
    public ResponseEntity<?> testService(@RequestBody Map<String, Object> body) throws Exception {
        String fullClassName = (String) body.get("fullClassName");
        String methodName = (String) body.get("methodName");
        Object[] args = body.get("args") != null ?
                ((java.util.List<?>) body.get("args")).toArray() : new Object[0];
        Object expectedResult = body.get("expectedResult");

        DynamicTestExecutionService.TestResult result =
                dynamicTestExecutionService.executeTest(fullClassName, methodName, args, expectedResult);

        return ResponseEntity.ok(Map.of(
                "success", result.isSuccess(),
                "actualResult", result.getActualResult(),
                "expectedResult", result.getExpectedResult() != null ? result.getExpectedResult() : "not provided"
        ));
    }



    @GetMapping("/test-cases/execute")
    public ResponseEntity<?> executeAllTestCases() throws Exception {
        List<TestCaseExecutorService.TestCaseResult> results = testCaseExecutorService.executeAllTestCases();

        Map<String, Object> summary = new HashMap<>();
        int totalPassed = results.stream().mapToInt(TestCaseExecutorService.TestCaseResult::getPassed).sum();
        int totalFailed = results.stream().mapToInt(TestCaseExecutorService.TestCaseResult::getFailed).sum();

        summary.put("totalTestCases", results.size());
        summary.put("totalPassed", totalPassed);
        summary.put("totalFailed", totalFailed);
        summary.put("results", results);

        return ResponseEntity.ok(summary);
    }


    @GetMapping("/test-cases/execute/{fileName}")
    public ResponseEntity<?> executeTestCase(@PathVariable String fileName) throws Exception {
        TestCaseExecutorService.TestCaseResult result = testCaseExecutorService.executeTestCase(fileName);

        Map<String, Object> response = new HashMap<>();
        response.put("passed", result.getPassed());
        response.put("failed", result.getFailed());
        response.put("details", result);

        return ResponseEntity.ok(response);
    }


}