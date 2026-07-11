package com.example.ragagent.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class TraceContextProvider {
    private final Tracer tracer;

    public TraceContextProvider(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    public TraceContextSnapshot current() {
        if (tracer == null) {
            return TraceContextSnapshot.empty();
        }
        Span span = tracer.currentSpan();
        if (span == null || span.context() == null) {
            return TraceContextSnapshot.empty();
        }
        return new TraceContextSnapshot(span.context().traceId(), span.context().spanId());
    }

    public Span currentSpan() {
        if (tracer == null) {
            return null;
        }
        return tracer.currentSpan();
    }
}
