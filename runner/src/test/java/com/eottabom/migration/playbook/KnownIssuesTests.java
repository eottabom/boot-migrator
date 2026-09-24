package com.eottabom.migration.playbook;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eottabom.migration.plan.MigrationPlanner;
import com.eottabom.migration.playbook.KnownIssues.Match;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KnownIssuesTests {

	private final KnownIssues issues = KnownIssues.load(Path.of("../playbook/known-issues.yml"));

	@Test
	void validatesRegistryFormatIdsAndStageKeys() {
		Set<String> ids = new HashSet<>();
		Set<String> stageKeys = new HashSet<>(MigrationPlanner.BOOT_STAGES);
		stageKeys.addAll(List.of("java21", "java25", "gradle"));
		for (KnownIssues.Issue issue : this.issues.issues()) {
			assertThat(ids.add(issue.id())).as("중복 id " + issue.id()).isTrue();
			if (issue.stage() != null) {
				assertThat(stageKeys).as(issue.id()).contains(issue.stage());
			}
		}
		stageKeys.forEach((key) -> assertThat(this.issues.guide(key)).as("guide " + key).isNotNull());
		assertThat(this.issues.failureHints()).isNotEmpty();
	}

	@ParameterizedTest
	@CsvSource({ "4.0, org.springframework.boot:spring-boot, 4.0.0, boot40-jackson3, boot40-mongodb-properties",
			"4.0, org.mongodb:mongodb-driver-sync, 5.5.0, boot40-mongodb-properties, ''" })
	void stageIssueWithRequiresMatchesOnlyWhenDependencyPresent(String stage, String module, String version,
			String expectedId, String unexpectedId) {
		Map<String, String> deps = Map.of(module, version);
		List<String> matched = ids(this.issues.match(stage, deps, deps));
		assertThat(matched).contains(expectedId);
		if (!unexpectedId.isEmpty()) {
			assertThat(matched).doesNotContain(unexpectedId);
		}
	}

	@ParameterizedTest
	@CsvSource({ "3.4, 6.5.2.Final, 6.6.4.Final, hibernate-66", "3.3, 6.4.4.Final, 6.5.2.Final, hibernate-hhh18378" })
	void libraryIssuesMatchByCrossesAndAffected(String stage, String beforeVersion, String afterVersion,
			String expectedIssueId) {
		Map<String, String> before = Map.of("org.hibernate.orm:hibernate-core", beforeVersion);
		Map<String, String> after = Map.of("org.hibernate.orm:hibernate-core", afterVersion);

		List<Match> matches = this.issues.match(stage, before, after);

		assertThat(ids(matches)).contains(expectedIssueId);
		if ("hibernate-66".equals(expectedIssueId)) {
			assertThat(ids(matches)).doesNotContain("hibernate-hhh18378", "hibernate-7");
			assertThat(matches.stream()
				.filter((m) -> m.issue().id().equals("hibernate-66"))
				.findFirst()
				.orElseThrow()
				.trigger()).isEqualTo("`org.hibernate.orm:hibernate-core` 6.5.2.Final → 6.6.4.Final");
		}
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
