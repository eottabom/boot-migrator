package com.eottabom.migration.plan;

import com.eottabom.migration.playbook.Compatibility;
import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.MigrationRequest;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlannerTest {

    static final Path PLAYBOOK = Path.of("../playbook");

    private final MigrationPlanner planner = new MigrationPlanner(Compatibility.load(PLAYBOOK.resolve("compatibility.yml")));

    @Test
    void plansFromNextStageToTargetAndKeepsSupportedJava() {
        MigrationPlan plan = planner.plan(project("3.2.8", "8.8", 17), request("3.5", "auto"));

        assertThat(plan.stageNames()).isEqualTo("3.3 3.4 3.5");
        assertThat(plan.stages().get(0).recipe()).isEqualTo("com.eottabom.rewrite.spring.SpringBootStep_3_3");
        assertThat(plan.targetJava()).isNull();
        assertThat(plan.notes()).anyMatch(n -> n.contains("Java 17") && n.contains("유지"));
    }

    @Test
    void insertsGradleStageOnlyBeforeBootStageThatNeedsIt() {
        MigrationPlan plan = planner.plan(project("3.2.8", "8.3", 17), request("3.5", "auto"));

        assertThat(plan.stageNames()).isEqualTo("3.3 gradle8.14 3.4 3.5");
        assertThat(plan.stages().get(1).kind()).isEqualTo(Stage.Kind.GRADLE);
        assertThat(plan.stages().get(1).issueKey()).isEqualTo("gradle");
    }

    @Test
    void boot4RequiresGradle814() {
        MigrationPlan plan = planner.plan(project("3.5.0", "8.8", 21), request(null, "auto"));

        assertThat(plan.stageNames()).isEqualTo("gradle8.14 4.0 4.1");
    }

    @Test
    void notesGradle9IsNotListedForBoot3() {
        MigrationPlan plan = planner.plan(project("3.4.0", "9.1.0", 21), request("3.5", "auto"));

        assertThat(plan.stageNames()).isEqualTo("3.5");
        assertThat(plan.notes()).anyMatch(n -> n.contains("공식 지원 목록"));
    }

    @Test
    void doesNotUpgradeGradleAutomaticallyFromBoot2() {
        MigrationPlan plan = planner.plan(project("2.7.18", "7.4", 11), request("3.0", "auto"));

        assertThat(plan.stageNames()).isEqualTo("3.0");
        assertThat(plan.notes()).anyMatch(n -> n.contains("Boot 2.x 에서 먼저 Gradle"))
                .anyMatch(n -> n.contains("Java 11 → 17"));
    }

    @Test
    void latestPicksNewestLtsSupportedByTarget() {
        assertThat(planner.plan(project("3.3.5", "8.8", 17), request("3.4", "latest")).stageNames()).isEqualTo("3.4 java21");
        assertThat(planner.plan(project("3.5.0", "8.14.3", 21), request("4.1", "latest")).stageNames()).isEqualTo("4.0 4.1 java25");
    }

    @Test
    void upgradesGradleFirstWhenItCannotRunOnJava21() {
        MigrationPlan plan = planner.plan(project("3.3.5", "8.4", 17), request("3.4", "21"));

        assertThat(plan.stageNames()).isEqualTo("3.4 gradle8.14 java21");
    }

    @Test
    void rejectsJavaUnsupportedByTarget() {
        assertThatThrownBy(() -> planner.plan(project("3.3.5", "8.8", 17), request("3.4", "25")))
                .hasMessageContaining("지원 범위(17 ~ 24) 밖");
    }

    @Test
    void oneShotRunsSingleTargetStage() {
        MigrationPlan plan = planner.plan(project("3.0.13", "8.8", 17), request("3.2", "none", true, false));

        assertThat(plan.stages()).extracting(Stage::name).containsExactly("3.2");
        assertThat(plan.stages()).extracting(Stage::recipe).containsExactly("com.eottabom.rewrite.spring.MigrateToSpringBoot_3_2");
    }

    @Test
    void upstreamOnlyUsesUpstreamRecipesAndStages() {
        MigrationPlan plan = planner.plan(project("3.5.1", "8.14", 17), request(null, "none", false, true));

        assertThat(plan.targetBoot()).isEqualTo("4.0");
        assertThat(plan.stages()).extracting(Stage::recipe)
                .containsExactly("com.eottabom.rewrite.spring.upstream.UpgradeSpringBootStep_4_0");
        assertThat(planner.plan(project("2.7.18", "7.6", 17), request("3.0", "none", false, true)).stages())
                .extracting(Stage::recipe).containsExactly("org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0");
    }

    @Test
    void emptyPlanWhenAlreadyAtTarget() {
        assertThat(planner.plan(project("3.5.3", "8.14", 21), request("3.4", "none")).isEmpty()).isTrue();
    }

    @Test
    void rejectsUnknownTargetAndMissingBootVersion() {
        assertThatThrownBy(() -> planner.plan(project("3.2.0", "8.8", 17), request("3.9", "auto")))
                .hasMessageContaining("목표 버전은");
        assertThatThrownBy(() -> planner.plan(project(null, "8.8", 17), request(null, "auto")))
                .hasMessageContaining("Spring Boot 버전을 찾지 못했습니다");
    }

    private static ProjectModel project(String boot, String gradle, Integer java) {
        return new ProjectModel(Path.of("."), boot, gradle, java, java, false, false);
    }

    private static MigrationRequest request(String boot, String java) {
        return request(boot, java, false, false);
    }

    private static MigrationRequest request(String boot, String java, boolean oneShot, boolean upstreamOnly) {
        return new MigrationRequest(Path.of("."), boot, java, "build", false, false, oneShot, upstreamOnly, false, false, false);
    }
}
