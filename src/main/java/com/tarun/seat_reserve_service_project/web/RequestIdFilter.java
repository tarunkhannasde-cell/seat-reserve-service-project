package com.tarun.seat_reserve_service_project.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Assigns every request a correlation id (honouring an inbound X-Request-Id), puts it in the MDC so
 * every log line for the request carries it, echoes it back, and writes one structured access-log
 * line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Logger access = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            if (!isProbe(path)) {
                access.atInfo()
                        .addKeyValue("http.method", request.getMethod())
                        .addKeyValue("http.path", path)
                        .addKeyValue("http.status", response.getStatus())
                        .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000.0)
                        .log("request completed"); // user_id / outcome / request_id come from the MDC
            }
            MDC.clear();
        }
    }

    private static boolean isProbe(String path) {
        return path.startsWith("/actuator") || path.equals("/livez") || path.equals("/readyz");
    }
}
