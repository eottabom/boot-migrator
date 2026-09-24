package com.eottabom.migration.plan;

import java.nio.file.Path;
import java.util.stream.Stream;

import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.MigrationRequest;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.playbook.Compatibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlannerTests {

	static final Path PLAYBOOK = Path.of("../playbook");

	private final MigrationPlanner planner = new MigrationPlanner(
			Compatibility.load(PLAYBOOK.resolve("compatibility.yml")));

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("planStageScenarios")
	void plansStages(String scenario, ProjectModel project, MigrationRequest request, String expectedStages) {
		MigrationPlan plan = this.planner.plan(project, request);
		assertThat(plan.stageNames()).isEqualTo(expectedStages);
	}

	// @formatter:off
	static Stream<Arguments> planStageScenarios() {
		return Stream.of(
			Arguments.of(
				"Boot 3.2.8 -> 3.5 순차 minor 단계 계획",
				project("3.2.8", "8.8", 17), request("3.5", "auto"),
				"3.3 3.4 3.5"
			),
			Arguments.of(
				"현재 Gradle(8.3)이 부족하면 다음 단계 직전에 Gradle 8.14 업그레이드 삽입",
				project("3.2.8", "8.3", 17), request("3.5", "auto"),
				"3.3 gradle8.14 3.4 3.5"
			),
			Arguments.of(
				"목표 버전 미지정(null) 시 최신 Boot(4.1) 및 필요 시 Gradle 8.14 삽입",
				project("3.5.0", "8.8", 21), request(null, "auto"),
				"gradle8.14 4.0 4.1"
			),
			Arguments.of(
				"Gradle 9.1.0 호환 환경에서 3.4 -> 3.5 단일 단계 계획",
				project("3.4.0", "9.1.0", 21), request("3.5", "auto"),
				"3.5"
			),
			Arguments.of(
				"Boot 2.7.18 -> 3.0 마이그레이션",
				project("2.7.18", "7.4", 11), request("3.0", "auto"),
				"3.0"
			),
			Arguments.of(
				"--java latest 옵션 지정 시 3.4 단계 후 Java 21 업그레이드 추가",
				project("3.3.5", "8.8", 17), request("3.4", "latest"),
				"3.4 java21"
			),
			Arguments.of(
				"Boot 3.5.0 -> 4.1 마이그레이션 시 최신 Java 25 업그레이드 추가",
				project("3.5.0", "8.14.3", 21), request("4.1", "latest"),
				"4.0 4.1 java25"
			),
			Arguments.of(
				"Gradle(8.4) 업그레이드와 명시적 Java 21 업그레이드가 함께 필요한 경우",
				project("3.3.5", "8.4", 17), request("3.4", "21"),
				"3.4 gradle8.14 java21"
			),
			Arguments.of(
				"이미 목표 버전보다 상위 버전인 경우 빈 단계 계획 반환",
				project("3.5.3", "8.14", 21), request("3.4", "none"),
				""
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("decisionNoteScenarios")
	void explainsJavaAndGradleDecisions(String scenario, ProjectModel project, MigrationRequest request, String note) {
		MigrationPlan plan = this.planner.plan(project, request);
		assertThat(plan.notes()).anyMatch((n) -> n.contains(note));
	}

	// @formatter:off
	static Stream<Arguments> decisionNoteScenarios() {
		return Stream.of(
			Arguments.of(
				"Java 버전이 지원 범위 내에 있어 유지됨을 설명",
				project("3.2.8", "8.8", 17), request("3.5", "auto"),
				"Java 17 는 Boot 3.5 지원 범위(17 ~ 25) 안이라 유지한다"
			),
			Arguments.of(
				"Gradle 9.1.0이 공식 지원 목록에 있음을 안내",
				project("3.4.0", "9.1.0", 21), request("3.5", "auto"),
				"공식 지원 목록"
			),
			Arguments.of(
				"Boot 2.x에서 먼저 Gradle을 올려야 함을 설명",
				project("2.7.18", "7.4", 11), request("3.0", "auto"),
				"Boot 2.x 에서 먼저 Gradle"
			),
			Arguments.of(
				"Java 11에서 17로 자동 상향됨을 설명",
				project("2.7.18", "7.4", 11), request("3.0", "auto"),
				"Java 11 → 17"
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("stageRecipeScenarios")
	void choosesStageRecipe(String scenario, ProjectModel project, MigrationRequest request, String recipe) {
		MigrationPlan plan = this.planner.plan(project, request);
		assertThat(plan.stages()).extracting(Stage::recipe).containsExactly(recipe);
	}

	// @formatter:off
	static Stream<Arguments> stageRecipeScenarios() {
		return Stream.of(
			Arguments.of(
				"기본 마이그레이션 단계 레시피 선택 (SpringBootStep)",
				project("3.2.8", "8.14", 17), request("3.3", "none", false, false),
				"com.eottabom.rewrite.spring.SpringBootStep_3_3"
			),
			Arguments.of(
				"One-shot 옵션 시 종합 레시피 선택 (MigrateToSpringBoot)",
				project("3.0.13", "8.14", 17), request("3.2", "none", true, false),
				"com.eottabom.rewrite.spring.MigrateToSpringBoot_3_2"
			),
			Arguments.of(
				"Upstream only 옵션 시 업스트림 단계 레시피 선택",
				project("3.5.1", "8.14", 17), request("4.0", "none", false, true),
				"com.eottabom.rewrite.spring.upstream.UpgradeSpringBootStep_4_0"
			),
			Arguments.of(
				"Boot 2.7 -> 3.0 upstream only 시 공식 오픈소스 레시피 선택",
				project("2.7.18", "8.14", 17), request("3.0", "none", false, true),
				"org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_0"
			)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("invalidRequestScenarios")
	void rejectsInvalidRequest(String scenario, ProjectModel project, MigrationRequest request,
			String expectedMessage) {
		assertThatThrownBy(() -> this.planner.plan(project, request)).hasMessageContaining(expectedMessage);
	}

	// @formatter:off
	static Stream<Arguments> invalidRequestScenarios() {
		return Stream.of(
			Arguments.of(
				"Java 25는 Boot 3.4 지원 범위 밖",
				project("3.3.5", "8.8", 17), request("3.4", "25"),
				"지원 범위(17 ~ 24) 밖"
			),
			Arguments.of(
				"미지원 목표 버전(3.9) 요청 시 거부",
				project("3.2.0", "8.8", 17), request("3.9", "auto"),
				"목표 버전은"
			),
			Arguments.of(
				"Spring Boot 버전을 찾지 못한 프로젝트 모델 거부",
				project(null, "8.8", 17), request(null, "auto"),
				"Spring Boot 버전을 찾지 못했습니다"
			)
		);
	}
	// @formatter:on

	@Test
	void gradleStageUsesGradleIssueKey() {
		Stage gradle = this.planner.plan(project("3.2.8", "8.3", 17), request("3.4", "auto")).stages().get(1);

		assertThat(gradle.kind()).isEqualTo(Stage.Kind.GRADLE);
		assertThat(gradle.issueKey()).isEqualTo("gradle");
	}

	private static ProjectModel project(String boot, String gradle, Integer java) {
		return new ProjectModel(Path.of("."), boot, gradle, java, java, false, false);
	}

	private static MigrationRequest request(String boot, String java) {
		return request(boot, java, false, false);
	}

	private static MigrationRequest request(String boot, String java, boolean oneShot, boolean upstreamOnly) {
		return new MigrationRequest(Path.of("."), boot, java, "build", false, false, oneShot, upstreamOnly, false,
				false, false);
	}

}
