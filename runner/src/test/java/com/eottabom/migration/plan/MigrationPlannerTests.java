package com.eottabom.migration.plan;

import java.nio.file.Path;

import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.MigrationRequest;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.playbook.Compatibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlannerTests {

	static final Path PLAYBOOK = Path.of("../playbook");

	private final MigrationPlanner planner = new MigrationPlanner(
			Compatibility.load(PLAYBOOK.resolve("compatibility.yml")));

	@ParameterizedTest
	@CsvSource(nullValues = "null", emptyValue = "", value = {
			// 현재 Boot, Gradle, Java, 목표 Boot, --java, 단계
			"3.2.8, 8.8, 17, 3.5, auto, 3.3 3.4 3.5", "3.2.8, 8.3, 17, 3.5, auto, 3.3 gradle8.14 3.4 3.5",
			"3.5.0, 8.8, 21, null, auto, gradle8.14 4.0 4.1", "3.4.0, 9.1.0, 21, 3.5, auto, 3.5",
			"2.7.18, 7.4, 11, 3.0, auto, 3.0", "3.3.5, 8.8, 17, 3.4, latest, 3.4 java21",
			"3.5.0, 8.14.3, 21, 4.1, latest, 4.0 4.1 java25", "3.3.5, 8.4, 17, 3.4, 21, 3.4 gradle8.14 java21",
			"3.5.3, 8.14, 21, 3.4, none, ''" })
	void plansStages(String boot, String gradle, int java, String target, String javaOption, String stages) {
		MigrationPlan plan = this.planner.plan(project(boot, gradle, java), request(target, javaOption, false, false));

		assertThat(plan.stageNames()).isEqualTo(stages);
	}

	@ParameterizedTest
	@CsvSource({ "3.2.8, 8.8, 17, 3.5, Java 17 는 Boot 3.5 지원 범위(17 ~ 25) 안이라 유지한다", "3.4.0, 9.1.0, 21, 3.5, 공식 지원 목록",
			"2.7.18, 7.4, 11, 3.0, Boot 2.x 에서 먼저 Gradle", "2.7.18, 7.4, 11, 3.0, Java 11 → 17" })
	void explainsJavaAndGradleDecisions(String boot, String gradle, int java, String target, String note) {
		MigrationPlan plan = this.planner.plan(project(boot, gradle, java), request(target, "auto", false, false));

		assertThat(plan.notes()).anyMatch((n) -> n.contains(note));
	}

	@ParameterizedTest
	@CsvSource({ "3.2.8, 3.3, false, false, com.eottabom.rewrite.spring.SpringBootStep_3_3",
			"3.0.13, 3.2, true, false, com.eottabom.rewrite.spring.MigrateToSpringBoot_3_2",
			"3.5.1, 4.0, false, true, com.eottabom.rewrite.spring.upstream.UpgradeSpringBootStep_4_0",
			"2.7.18, 3.0, false, true, org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0" })
	void choosesStageRecipe(String boot, String target, boolean oneShot, boolean upstreamOnly, String recipe) {
		MigrationPlan plan = this.planner.plan(project(boot, "8.14", 17),
				request(target, "none", oneShot, upstreamOnly));

		assertThat(plan.stages()).extracting(Stage::recipe).containsExactly(recipe);
	}

	@ParameterizedTest
	@CsvSource(nullValues = "null", value = { "3.3.5, 3.4, 25, 지원 범위(17 ~ 24) 밖", "3.2.0, 3.9, auto, 목표 버전은",
			"null, null, auto, Spring Boot 버전을 찾지 못했습니다" })
	void rejectsInvalidRequest(String boot, String target, String javaOption, String message) {
		assertThatThrownBy(() -> this.planner.plan(project(boot, "8.8", 17), request(target, javaOption, false, false)))
			.hasMessageContaining(message);
	}

	@Test
	void gradleStageUsesGradleIssueKey() {
		Stage gradle = this.planner.plan(project("3.2.8", "8.3", 17), request("3.4", "auto", false, false))
			.stages()
			.get(1);

		assertThat(gradle.kind()).isEqualTo(Stage.Kind.GRADLE);
		assertThat(gradle.issueKey()).isEqualTo("gradle");
	}

	private static ProjectModel project(String boot, String gradle, Integer java) {
		return new ProjectModel(Path.of("."), boot, gradle, java, java, false, false);
	}

	private static MigrationRequest request(String boot, String java, boolean oneShot, boolean upstreamOnly) {
		return new MigrationRequest(Path.of("."), boot, java, "build", false, false, oneShot, upstreamOnly, false,
				false, false);
	}

}
