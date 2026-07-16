package com.tongji.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 将 MVC 的异步响应（当前主要是 RAG SSE）绑定到受控线程池。
 */
@Component
public class WebMvcAsyncConfig implements WebMvcConfigurer {
    private final AsyncTaskExecutor mvcAsyncExecutor;

    public WebMvcAsyncConfig(@Qualifier("mvcAsyncExecutor") AsyncTaskExecutor mvcAsyncExecutor) {
        this.mvcAsyncExecutor = mvcAsyncExecutor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor);
    }
}
