package com.paytm.seats.observability;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.util.Set;
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
 * Assigns a correlation id (honouring an inbound X-Request-Id), echoes it on the response and emits one
 * structured access-log line per request carrying the MDC fields set while handling it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger("access");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Set<String> QUIET = Set.of("/metrics", "/livez", "/readyz", "/health");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(HEADER);
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        MDC.put(RequestContext.REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            if (!QUIET.contains(request.getRequestURI()) || status >= 400) {
                if (status >= 500) {
                    log.error("request", kv("method", request.getMethod()), kv("path", request.getRequestURI()),
                            kv("status", status), kv("latency_ms", latencyMs));
                } else {
                    log.info("request", kv("method", request.getMethod()), kv("path", request.getRequestURI()),
                            kv("status", status), kv("latency_ms", latencyMs));
                }
            }
            MDC.clear();
        }
    }
}
