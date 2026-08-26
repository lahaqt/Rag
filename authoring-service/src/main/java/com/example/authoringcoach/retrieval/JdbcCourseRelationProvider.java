package com.example.authoringcoach.retrieval;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads only administrator-approved, enabled course relationships. */
public final class JdbcCourseRelationProvider implements CourseRelationProvider {
    private final JdbcTemplate jdbcTemplate;

    public JdbcCourseRelationProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<CourseRelation> relatedCourses(String anchorCourseId, String query) {
        return jdbcTemplate.query("""
                SELECT r.related_course_id, r.relation_type, r.scope_weight
                FROM course_retrieval_relations r
                JOIN courses c ON c.id=r.related_course_id
                WHERE r.anchor_course_id=? AND r.enabled=true AND c.archived=false AND c.published=true
                ORDER BY r.scope_weight DESC, r.related_course_id
                """, (rs, row) -> new CourseRelation(
                rs.getString("related_course_id"), tier(rs.getString("relation_type")),
                rs.getDouble("scope_weight")), anchorCourseId);
    }

    private RetrievalScopeTier tier(String relationType) {
        return switch (relationType) {
            case "PREREQUISITE", "COREQUISITE", "EQUIVALENT" -> RetrievalScopeTier.RELATED;
            case "PROGRAM" -> RetrievalScopeTier.PROGRAM;
            case "DEPARTMENT", "INSTITUTION" -> RetrievalScopeTier.SCHOOL;
            default -> throw new IllegalArgumentException("Unsupported course relation type: " + relationType);
        };
    }
}
