package com.eottabom.rewrite.gradle;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.openrewrite.test.SourceSpecs.text;
import static org.openrewrite.toml.Assertions.toml;

class UpgradeVersionCatalogTests implements RewriteTest {

	private static final String BEFORE = """
			[versions]
			spring-boot = "3.3.5"

			[plugins]
			spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
			""";

	private static final String AFTER = """
			[versions]
			spring-boot = "3.4.1"

			[plugins]
			spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new UpgradeVersionCatalog(List.of("plugin org.springframework.boot 3.4.1")));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void rewrites(String scenario, SourceSpecs source) {
		rewriteRun(source);
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of("toml 로 파싱된 catalog", toml(BEFORE, AFTER, (s) -> s.path("gradle/libs.versions.toml"))),
			Arguments.of("plain text 로 파싱된 catalog", text(BEFORE, AFTER, (s) -> s.path("gradle/libs.versions.toml"))),
			Arguments.of("이름이 다른 catalog", toml(BEFORE, AFTER, (s) -> s.path("gradle/deps.versions.toml"))),
			Arguments.of("gradle 디렉토리 밖의 toml 은 건드리지 않음", toml(BEFORE, (s) -> s.path("config/libs.versions.toml")))
		);
	}
	// @formatter:on

	@Test
	void keepsVersionWhenPatternCannotBeResolved() {
		rewriteRun((spec) -> spec.recipe(new UpgradeVersionCatalog(List.of("plugin org.springframework.boot 3.4.x"))),
				toml(BEFORE, (s) -> s.path("gradle/libs.versions.toml")));
	}

}
