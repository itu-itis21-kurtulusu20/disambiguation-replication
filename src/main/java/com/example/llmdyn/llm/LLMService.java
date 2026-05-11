package com.example.llmdyn.llm;

public interface LLMService {
    String call(String prompt) throws Exception;
}

