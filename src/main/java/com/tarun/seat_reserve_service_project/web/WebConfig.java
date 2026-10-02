package com.tarun.seat_reserve_service_project.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.tarun.seat_reserve_service_project.auth.AuthenticatedUserResolver;


@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthenticatedUserResolver userResolver;

    public WebConfig(AuthenticatedUserResolver userResolver) {
        this.userResolver = userResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(userResolver);
    }
}
