package com.example.ragagent.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Creates semantic Agent spans on top of the HTTP/client spans supplied by
 * Micrometer. AgentTrace remains the auditable business event stream; these
 * spans make the same stages visible in an OpenTelemetry backend.
 */
@Component
public class AgentStageTracer {
    private final Tracer tracer;

    public AgentStageTracer(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    public <T> T inSpan(String name, Map<String, String> tags, Supplier<T> operation) {
        return inSpan(name, tags, null, operation);
    }

    public <T> T inSpan(
            String name,
            Map<String, String> tags,
            Span parentSpan,
            Supplier<T> operation
    ) {
        if (tracer == null) {
            return operation.get();
        }
        Span span = (parentSpan == null ? tracer.nextSpan() : tracer.nextSpan(parentSpan))
                .name(name)
                .start();
        if (tags != null) {
            tags.forEach((key, value) -> span.tag(key, value == null ? "" : value));
        }
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            T result = operation.get();
            span.tag("agent.outcome", "ok");
            return result;
        } catch (RuntimeException exception) {
            span.tag("agent.outcome", "error");
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
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
}
