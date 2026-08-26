package com.example.authoringcoach.retrieval;

/** The authoritative course is a fail-closed boundary; supplemental scopes may not replace it. */
public final class CurrentCourseRetrievalException extends RuntimeException {
    private final String courseId;

    public CurrentCourseRetrievalException(String courseId, String reason, Throwable cause) {
        super("authoritative course retrieval failed for " + courseId + ": " + reason, cause);
        this.courseId = courseId;
    }

    public String courseId() {
        return courseId;
    }
}
