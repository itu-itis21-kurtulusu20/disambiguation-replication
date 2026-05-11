package com.example.llmdyn.model;

import java.util.List;

public class TestCase {
    private String prompt;
    private String fullClassName;
    private List<TestScenario> tests;

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getFullClassName() { return fullClassName; }
    public void setFullClassName(String fullClassName) { this.fullClassName = fullClassName; }

    public List<TestScenario> getTests() { return tests; }
    public void setTests(List<TestScenario> tests) { this.tests = tests; }

    public static class TestScenario {
        private String methodName;
        private List<Object> args;
        private Object expectedResult;

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public List<Object> getArgs() { return args; }
        public void setArgs(List<Object> args) { this.args = args; }

        public Object getExpectedResult() { return expectedResult; }
        public void setExpectedResult(Object expectedResult) { this.expectedResult = expectedResult; }
    }
}