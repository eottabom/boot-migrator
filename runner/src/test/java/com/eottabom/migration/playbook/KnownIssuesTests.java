package com.eottabom.migration.playbook;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.playbook.KnownIssues.Match;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class KnownIssuesTests {

	private final KnownIssues issues = KnownIssues.load(Path.of("../playbook/known-issues.yml"));

	@TestFactory
	Stream<DynamicTest> validatesRegistryFormatIdsAndStageKeys() {
		Set<String> ids = new HashSet<>();
		Set<String> stageKeys = new HashSet<>(MigrationPlanner.BOOT_STAGES);
		stageKeys.addAll(List.of("java21", "java25", "gradle"));

		Stream<DynamicTest> issueTests = this.issues.issues()
			.stream()
			.map((issue) -> DynamicTest.dynamicTest("issue: " + issue.id(), () -> {
				assertThat(ids.add(issue.id())).as("중복 id " + issue.id()).isTrue();
				if (issue.stage() != null) {
					assertThat(stageKeys).as(issue.id()).contains(issue.stage());
				}
			}));

		Stream<DynamicTest> guideTests = stageKeys.stream()
			.map((key) -> DynamicTest.dynamicTest("guide: " + key,
					() -> assertThat(this.issues.guide(key)).as("guide " + key).isNotNull()));

		DynamicTest hintsTest = DynamicTest.dynamicTest("failure hints not empty",
				() -> assertThat(this.issues.failureHints()).isNotEmpty());

		return Stream.concat(Stream.concat(issueTests, guideTests), Stream.of(hintsTest));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("stageIssueWithRequiresCases")
	void stageIssueWithRequiresMatchesOnlyWhenDependencyPresent(String scenario, String stage, Map<String, String> deps,
			String expectedId, List<String> unexpectedIds) {
		List<String> matched = ids(this.issues.match(stage, deps, deps));
		assertThat(matched).contains(expectedId);
		for (String unexpectedId : unexpectedIds) {
			assertThat(matched).doesNotContain(unexpectedId);
		}
	}

	static Stream<Arguments> stageIssueWithRequiresCases() {
		return Stream.of(
				Arguments.of("의존성이 없을 때는 requires 조건이 걸린 이슈가 매칭되지 않는다", "4.0",
						Map.of("org.springframework.boot:spring-boot", "4.0.0"), "boot40-jackson3",
						List.of("boot40-mongodb-properties")),
				Arguments.of("해당 의존성(mongodb)이 있을 때만 requires 조건 이슈가 매칭된다", "4.0",
						Map.of("org.mongodb:mongodb-driver-sync", "5.5.0"), "boot40-mongodb-properties", List.of()));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("libraryIssueMatchCases")
	void libraryIssuesMatchByCrossesAndAffected(String scenario, String stage, String beforeVersion,
			String afterVersion, String expectedIssueId, String expectedTrigger, List<String> unexpectedIds) {
		Map<String, String> before = Map.of("org.hibernate.orm:hibernate-core", beforeVersion);
		Map<String, String> after = Map.of("org.hibernate.orm:hibernate-core", afterVersion);

		List<Match> matches = this.issues.match(stage, before, after);

		assertThat(ids(matches)).contains(expectedIssueId);
		for (String unexpectedId : unexpectedIds) {
			assertThat(ids(matches)).doesNotContain(unexpectedId);
		}
		if (expectedTrigger != null) {
			assertThat(matches.stream()
				.filter((m) -> m.issue().id().equals(expectedIssueId))
				.findFirst()
				.orElseThrow()
				.trigger()).isEqualTo(expectedTrigger);
		}
	}

	static Stream<Arguments> libraryIssueMatchCases() {
		return Stream.of(
				Arguments.of("Hibernate 6.5 -> 6.6 버전 교차 시 hibernate-66 이슈 매칭 및 트리거 문구 생성", "3.4", "6.5.2.Final",
						"6.6.4.Final", "hibernate-66", "`org.hibernate.orm:hibernate-core` 6.5.2.Final → 6.6.4.Final",
						List.of("hibernate-hhh18378", "hibernate-7")),
				Arguments.of("Hibernate 6.4 -> 6.5 버전 교차 시 hibernate-hhh18378 이슈 매칭", "3.3", "6.4.4.Final",
						"6.5.2.Final", "hibernate-hhh18378", null, List.of()));
	}

	@Test
	void showsAllStageIssuesWithoutDependencyInfo() {
		assertThat(this.issues.forStage("3.5")).extracting(KnownIssues.Issue::id)
			.contains("boot35-task-executor-name", "boot35-heapdump");
		assertThat(ids(this.issues.match("3.5", Map.of(), Map.of()))).contains("boot35-heapdump");
	}

	private static List<String> ids(List<Match> matches) {
		return matches.stream().map((m) -> m.issue().id()).toList();
	}

}
