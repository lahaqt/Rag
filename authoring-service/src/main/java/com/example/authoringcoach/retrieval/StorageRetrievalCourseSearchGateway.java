package com.example.authoringcoach.retrieval;

import com.example.authoringcoach.dto.VectorSearchRequest;
import com.example.authoringcoach.dto.VectorSearchResponse;
import com.example.authoringcoach.service.StorageRetrievalClient;
import java.util.Objects;

/** Adapter that preserves the existing one-course Content Service contract. */
public final class StorageRetrievalCourseSearchGateway implements CourseSearchGateway {
    private final StorageRetrievalClient delegate;

    public StorageRetrievalCourseSearchGateway(StorageRetrievalClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public VectorSearchResponse search(VectorSearchRequest request) {
        return delegate.search(request);
    }
}
