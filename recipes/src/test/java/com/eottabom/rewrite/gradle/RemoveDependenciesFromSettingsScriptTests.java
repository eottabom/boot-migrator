package com.eottabom.rewrite.gradle;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.settingsGradle;
import static org.openrewrite.gradle.Assertions.settingsGradleKts;

class RemoveDependenciesFromSettingsScriptTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new RemoveDependenciesFromSettingsScript());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void rewrites(String scenario, boolean kotlinDsl, String before, String after) {
		rewriteRun(source(kotlinDsl, before, after));
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"kotlin dsl",
				true,
				"""
				rootProject.name = "spring-benchmark"

				dependencies {
				    runtimeOnly("org.springframework.boot:spring-boot-mongodb:4.1.1")
				}
				""",
				"""
				rootProject.name = "spring-benchmark"
				"""
			),
			Arguments.of(
				"groovy dsl",
				false,
				"""
				rootProject.name = 'app'
				dependencies {
				    runtimeOnly 'org.springframework.boot:spring-boot-mongodb:4.1.1'
				}
				include 'api'
				""",
				"""
				rootProject.name = 'app'
				include 'api'
				"""
			)
		);
	}
	// @formatter:on

	private static SourceSpecs source(boolean kotlinDsl, String before, String after) {
		if (kotlinDsl) {
			return (after != null) ? settingsGradleKts(before, after) : settingsGradleKts(before);
		}
		return (after != null) ? settingsGradle(before, after) : settingsGradle(before);
	}

	@Test
	void keepsNestedDependenciesAndBuildFiles() {
		rewriteRun(settingsGradle("""
				buildscript {
				    dependencies {
				        classpath 'com.example:plugin:1.0'
				    }
				}
				rootProject.name = 'app'
				"""), buildGradle("""
				plugins { id 'java' }
				dependencies {
				    implementation 'org.apache.commons:commons-lang3:3.17.0'
				}
				"""));
	}

}
