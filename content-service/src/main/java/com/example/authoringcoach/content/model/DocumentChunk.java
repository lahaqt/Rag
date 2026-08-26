package com.example.authoringcoach.content.model;

public class DocumentChunk {
    private final String id;
    private final String materialId;
    private final String documentName;
    private final String courseId;
    private final int chunkIndex;
    private final String content;

    public DocumentChunk(
            String id,
            String materialId,
            String documentName,
            String courseId,
            int chunkIndex,
            String content
    ) {
        this.id = id;
        this.materialId = materialId;
        this.documentName = documentName;
        this.courseId = courseId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public String getMaterialId() {
        return materialId;
    }

    public String getMaterialName() {
        return documentName;
    }

    public String getCourseContentSpaceId() {
        return courseId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getContent() {
        return content;
    }

}
