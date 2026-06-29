package com.example.ragagent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceContextFilter extends OncePerRequestFilter {
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";

    private final TraceContextProvider traceContextProvider;

    public TraceContextFilter(TraceContextProvider traceContextProvider) {
        this.traceContextProvider = traceContextProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContextSnapshot context = traceContextProvider.current();
            if (context.available() && !response.isCommitted()) {
                response.setHeader(TRACE_ID_HEADER, context.traceId());
                response.setHeader(SPAN_ID_HEADER, context.spanId());
            }
        }
    }
}
