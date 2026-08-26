package com.example.authoringcoach.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.authoringcoach.retrieval.CourseRelationProvider.CourseRelation;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalScopePlannerTests {
    @Test
    void anchorsTheCurrentCourseAndDeduplicatesOrderedSupplementalRelations() {
        CourseRelationProvider relations = (courseId, query) -> List.of(
                new CourseRelation("school-course", RetrievalScopeTier.SCHOOL, null),
                new CourseRelation("related-course", RetrievalScopeTier.RELATED, 0.8),
                new CourseRelation("related-course", RetrievalScopeTier.PROGRAM, null),
                new CourseRelation(courseId, RetrievalScopeTier.RELATED, null),
                new CourseRelation("program-course", RetrievalScopeTier.PROGRAM, null));

        RetrievalScopePlan plan = new RetrievalScopePlanner(relations).plan("anchor-course", "beam stress");

        assertThat(plan.scopes()).extracting(RetrievalScope::courseId)
                .containsExactly("anchor-course", "related-course", "program-course", "school-course");
        assertThat(plan.scopes().get(0).authority()).isEqualTo(EvidenceAuthority.AUTHORITATIVE);
        assertThat(plan.scopes().subList(1, plan.scopes().size()))
                .allMatch(scope -> scope.authority() == EvidenceAuthority.SUPPLEMENTAL);
        assertThat(plan.scopes().get(1).rankingWeight()).isEqualTo(0.8);
        assertThat(plan.tierQuotas()).containsEntry(RetrievalScopeTier.CURRENT, 6)
                .containsEntry(RetrievalScopeTier.RELATED, 2)
                .containsEntry(RetrievalScopeTier.PROGRAM, 2)
                .containsEntry(RetrievalScopeTier.SCHOOL, 1);
    }

    @Test
    void boundsCourseFanOutPerTier() {
        RetrievalScopePlanner planner = new RetrievalScopePlanner((courseId, query) -> List.of(
                new CourseRelation("related-3", RetrievalScopeTier.RELATED, 0.6),
                new CourseRelation("related-1", RetrievalScopeTier.RELATED, 0.8),
                new CourseRelation("related-2", RetrievalScopeTier.RELATED, 0.7),
                new CourseRelation("school-2", RetrievalScopeTier.SCHOOL, 0.2),
                new CourseRelation("school-1", RetrievalScopeTier.SCHOOL, 0.3)));

        RetrievalScopePlan plan = planner.plan("anchor", "beam stress");

        assertThat(plan.scopes()).filteredOn(scope -> scope.tier() == RetrievalScopeTier.RELATED).hasSize(2);
        assertThat(plan.scopes()).filteredOn(scope -> scope.tier() == RetrievalScopeTier.SCHOOL).hasSize(1);
        assertThat(plan.scopes()).extracting(RetrievalScope::courseId)
                .contains("related-1", "related-2", "school-1")
                .doesNotContain("related-3", "school-2");
    }
}
