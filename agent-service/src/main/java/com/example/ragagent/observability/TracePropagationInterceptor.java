package com.example.ragagent.observability;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

@Component
public class TracePropagationInterceptor implements ClientHttpRequestInterceptor {
    private final TraceContextProvider traceContextProvider;

    public TracePropagationInterceptor(TraceContextProvider traceContextProvider) {
        this.traceContextProvider = traceContextProvider;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        TraceContextSnapshot context = traceContextProvider.current();
        if (context.available() && !request.getHeaders().containsKey("traceparent")) {
            request.getHeaders().set("traceparent", "00-" + context.traceId() + "-" + context.spanId() + "-01");
        }
        return execution.execute(request, body);
    }
}
