package com.github.josemattioni.ledger_split_engine.adapter.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A {@code @Component} and not a {@code @Configuration}, so that the {@code @WebMvcTest} slices load it too: the
 * controller tests then run with the same query-parameter rule as the application.
 *
 * <p>Only the API's own paths: the interceptor must not run on Spring Boot's {@code /error} dispatch, where a
 * 400 thrown for a stray query string would be a failure inside the error page.
 */
@Component
class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new QueryParameterInterceptor()).addPathPatterns("/api/v1/**");
    }
}
