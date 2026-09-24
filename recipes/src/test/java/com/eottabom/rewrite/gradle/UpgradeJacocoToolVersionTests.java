package com.eottabom.rewrite.gradle;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UpgradeJacocoToolVersionTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new UpgradeJacocoToolVersion("0.8.15"));
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
				"upgrades old tool version only in jacoco block",
				false,
				"""
				subprojects {
				    jacoco {
				        toolVersion = "0.8.7"
				    }
				    checkstyle {
				        toolVersion = "10.0"
				    }
				}
				""",
				"""
				subprojects {
				    jacoco {
				        toolVersion = "0.8.15"
				    }
				    checkstyle {
				        toolVersion = "10.0"
				    }
				}
				"""
			),
			Arguments.of(
				"keeps newer version",
				false,
				"""
				jacoco {
				    toolVersion = '0.8.16'
				}
				""",
				null
			),
			Arguments.of(
				"kotlin dsl",
				true,
				"""
				plugins {
				    jacoco
				}
				jacoco {
				    toolVersion = "0.8.7"
				}
				""",
				"""
				plugins {
				    jacoco
				}
				jacoco {
				    toolVersion = "0.8.15"
				}
				"""
			)
		);
	}
	// @formatter:on

	private static SourceSpecs source(boolean kotlinDsl, String before, String after) {
		if (kotlinDsl) {
			return (after != null) ? buildGradleKts(before, after) : buildGradleKts(before);
		}
		return (after != null) ? buildGradle(before, after) : buildGradle(before);
	}

}
