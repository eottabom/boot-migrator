package com.eottabom.migration.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.eottabom.migration.playbook.KnownIssues.FailureHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class StageReportTests {

	static final List<FailureHint> HINTS = List
		.of(new FailureHint("NoSuchBeanDefinitionException.*'taskExecutor'", "applicationTaskExecutor 로 바꾼다"));

	@TempDir
	Path project;

	static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of("단일 예외 및 실패 위치(소스 코드 줄번호) 추출", "demo.app.OrderTest",
						"java.lang.AssertionError: expected 1\n\tat demo.app.OrderTest.saves(OrderTest.java:12)",
						"OrderTest", "saves", "AssertionError", "expected 1", "OrderTest.java:12", null),
				Arguments.of("중첩 예외(Caused by) 시 가장 안쪽(root cause) 예외 추출", "demo.app.OrderTest$WhenPaid$Refund",
						"java.lang.IllegalStateException: outer\nCaused by: org.x.InnerException: root cause",
						"OrderTest", "WhenPaid > Refund > saves", "InnerException", "root cause", null, null),
				Arguments.of("알려진 문제 힌트(FailureHint)와 일치하는 예외 힌트 추출", "demo.app.AsyncTest",
						"org.springframework.beans.factory.NoSuchBeanDefinitionException: No bean named 'taskExecutor' available",
						"AsyncTest", "saves", "NoSuchBeanDefinitionException", "No bean named 'taskExecutor' available",
						null, "applicationTaskExecutor 로 바꾼다"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("failures")
	void extractsInnermostCauseLocationAndHint(String scenario, String classname, String stack, String simpleClass,
			String test, String exception, String message, String location, String hint) {
		Map<String, Object> failure = StageReport.testFailure(classname, "saves", "", stack, HINTS);

		assertThat((String) failure.get("cls")).endsWith(simpleClass);
		assertThat(failure).containsEntry("test", test)
			.containsEntry("exception", exception)
			.containsEntry("message", message)
			.containsEntry("location", location)
			.containsEntry("hint", hint);
	}

	@ParameterizedTest(name = "[{index}] {0} -> {1} ({2})")
	@CsvSource({ "1.2.3, 2.0.0, major", "1.2.3, 1.3.0, minor", "1.2.3, 1.2.4, patch", "6.6.2.Final, 6.6.3.Final, patch",
			"33.4.8-jre, 33.5.0-jre, minor" })
	void classifiesVersionChange(String before, String after, String level) {
		assertThat(StageReport.level(before, after)).isEqualTo(level);
	}

	@ParameterizedTest(name = "[{index}] 레시피: {0} -> 커스텀 fix={1}")
	@CsvSource({ "com.eottabom.rewrite.gradle.DeclareUsedDependency, true",
			"com.eottabom.rewrite.spring.SpringBootStep_3_4, false",
			"com.eottabom.rewrite.spring.MigrateToSpringBoot_3_4, false",
			"com.eottabom.rewrite.spring.upstream.UpgradeSpringBootStep_3_4, false",
			"com.eottabom.rewrite.CommonMigrationFixes, false",
			"org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_4, false", "demo.migration.RenameGreeting, true" })
	void countsOnlyCustomAndProjectRecipesAsFixes(String recipe, boolean custom) {
		assertThat(StageReport.isCustomFix(recipe, Set.of("demo.migration.RenameGreeting"))).isEqualTo(custom);
	}

	@Test
	void writesMarkdownAndJsonFromStageFiles() throws IOException {
		Path log = write("compile.log",
				this.project.resolve("src/A.java")
						+ ":3: warning: [removal] old() in A has been deprecated and marked for removal\n"
						+ this.project.resolve("src/A.java")
						+ ":3: warning: [removal] old() in A has been deprecated and marked for removal\n");
		write("build/test-results/test/TEST-demo.AppTest.xml",
				"""
						<testsuite name="demo.AppTest" tests="2" failures="1" errors="0">
						  <testcase classname="demo.AppTest" name="ok"/>
						  <testcase classname="demo.AppTest" name="boom"><failure message="x">java.lang.IllegalStateException: boom</failure></testcase>
						  <system-out><![CDATA[The use of configuration keys that have been renamed was found in the environment:

						Property source 'Config resource application.yml':
							Key: spring.redis.host
								Line: 3
								Replacement: spring.data.redis.host

						]]></system-out>
						</testsuite>
						""");
		Path find = write("scan.patch", "+++ b/src/A.java\n+    ObjectMapper m = /*~~>*/new ObjectMapper();\n");
		Path rewrite = write("rewrite.log", """
				Changes have been made to build.gradle by:
				    com.eottabom.rewrite.spring.SpringBootStep_3_4
				        com.eottabom.rewrite.gradle.RemoveDependencyVersion: {groupId=org.apache.kafka}
				Please review and commit the results.
				""");
		Path before = write("before.txt", "org.hibernate.orm:hibernate-core=6.5.2.Final\norg.old:lib=1.0\n");
		Path after = write("after.txt", "org.hibernate.orm:hibernate-core=6.6.4.Final\norg.new:lib=1.0\n");
		List<Map<String, Object>> issues = List
			.of(Map.of("id", "x", "mode", "REPORT_ONLY", "title", "제목", "detail", "설명"));

		StageReport.write(
				new StageReport.Input("3.4", this.project, log, rewrite, find, before, after, "1", "1", "1", issues,
						"https://guide", HINTS, Set.of()),
				this.project.resolve("out.md"), this.project.resolve("out.json"));

		String md = Files.readString(this.project.resolve("out.md"));
		assertThat(md).contains("# Spring Boot 3.4 마이그레이션 검증 리포트", "❌ 1 / 2 실패", "IllegalStateException",
				"`spring.redis.host` → `spring.data.redis.host`",
				"old() in A has been deprecated and marked for removal** 1곳 (src/A.java:3)",
				"com.eottabom.rewrite.gradle.RemoveDependencyVersion",
				"| `org.hibernate.orm:hibernate-core` | 6.5.2.Final | 6.6.4.Final | minor |", "추가된 의존성 `org.new:lib`",
				"**제목**", "`src/A.java` 의 `ObjectMapper m = new ObjectMapper();`");
		Map<String, Object> json = new Yaml().load(Files.readString(this.project.resolve("out.json")));
		assertThat((Map<String, Object>) json.get("tests")).containsEntry("total", 2);
		assertThat((List<Object>) json.get("changedFiles")).containsExactly("build.gradle");
		assertThat(json).containsEntry("compile", "ok").containsEntry("guide", "https://guide");
	}

	private Path write(String path, String content) throws IOException {
		Path file = this.project.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return file;
	}

}
