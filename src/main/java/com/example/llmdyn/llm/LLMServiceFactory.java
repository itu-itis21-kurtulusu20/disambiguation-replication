package com.example.llmdyn.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class LLMServiceFactory {

    @Value("${llm.provider:chatgpt}")
    private String provider;

    private final ApplicationContext context;

    public LLMServiceFactory(ApplicationContext context) {
        this.context = context;
    }

    public LLMService getService() {
        return context.getBean(provider, LLMService.class);
    }
}

