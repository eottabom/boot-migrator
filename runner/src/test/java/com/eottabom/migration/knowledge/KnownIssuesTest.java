package com.eottabom.migration.knowledge;

import com.eottabom.migration.knowledge.KnownIssues.Match;
import com.eottabom.migration.plan.MigrationPlanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnownIssuesTest {

    private final KnownIssues issues = KnownIssues.load(Path.of("../knowledge/known-issues.yml"));

    @Test
    void 레지스트리_형식과_id_중복_단계_키_검증() {
        Set<String> ids = new HashSet<>();
        Set<String> stageKeys = new HashSet<>(MigrationPlanner.BOOT_STAGES);
        stageKeys.addAll(List.of("java21", "java25", "gradle"));
        for (KnownIssues.Issue issue : issues.all()) {
            assertThat(ids.add(issue.id())).as("중복 id " + issue.id()).isTrue();
            if (issue.stage() != null) {
                assertThat(stageKeys).as(issue.id()).contains(issue.stage());
            }
        }
        stageKeys.forEach(key -> assertThat(issues.guide(key)).as("guide " + key).isNotNull());
        assertThat(issues.failureHints()).isNotEmpty();
    }

    @Test
    void requires_가_있는_단계_이슈는_해당_의존성이_있을_때만() {
        Map<String, String> withoutMongo = Map.of("org.springframework.boot:spring-boot", "4.0.0");
        Map<String, String> withMongo = Map.of("org.mongodb:mongodb-driver-sync", "5.5.0");

        assertThat(ids(issues.match("4.0", withoutMongo, withoutMongo))).doesNotContain("boot40-mongodb-properties")
                .contains("boot40-jackson3");
        assertThat(ids(issues.match("4.0", withMongo, withMongo))).contains("boot40-mongodb-properties");
    }

    @Test
    void 라이브러리_이슈는_crosses_와_affected_로_판단() {
        Map<String, String> before = Map.of("org.hibernate.orm:hibernate-core", "6.5.2.Final");
        Map<String, String> after = Map.of("org.hibernate.orm:hibernate-core", "6.6.4.Final");

        List<Match> matches = issues.match("3.4", before, after);

        assertThat(ids(matches)).contains("hibernate-66").doesNotContain("hibernate-hhh18378", "hibernate-7");
        assertThat(matches.stream().filter(m -> m.issue().id().equals("hibernate-66")).findFirst().orElseThrow().trigger())
                .isEqualTo("`org.hibernate.orm:hibernate-core` 6.5.2.Final → 6.6.4.Final");
        assertThat(ids(issues.match("3.3", Map.of("org.hibernate.orm:hibernate-core", "6.4.4.Final"),
                Map.of("org.hibernate.orm:hibernate-core", "6.5.2.Final")))).contains("hibernate-hhh18378");
    }

    @Test
    void 의존성_정보가_없으면_단계_이슈는_모두_보인다() {
        assertThat(issues.forStage("3.5")).extracting(KnownIssues.Issue::id).contains("boot35-task-executor-name", "boot35-heapdump");
        assertThat(ids(issues.match("3.5", Map.of(), Map.of()))).contains("boot35-heapdump");
    }

    private static List<String> ids(List<Match> matches) {
        return matches.stream().map(m -> m.issue().id()).toList();
    }
}
