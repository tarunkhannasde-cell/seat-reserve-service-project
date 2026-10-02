package com.tarun.seat_reserve_service_project.auth;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.tarun.seat_reserve_service_project.error.ApiException;



/** Injects {@link AuthenticatedUser} into controller methods from {@code Authorization: Bearer <jwt>}. */
@Component
public class AuthenticatedUserResolver implements HandlerMethodArgumentResolver {

    private final TokenService tokens;

    public AuthenticatedUserResolver(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == AuthenticatedUser.class;
    }

    @Override
    public AuthenticatedUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                             NativeWebRequest request, WebDataBinderFactory binderFactory) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw ApiException.unauthorized("missing bearer token");
        }
        String userId = tokens.verify(header.substring(7).trim());
        MDC.put("user_id", userId);
        return new AuthenticatedUser(userId);
    }
}
