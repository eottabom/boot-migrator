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
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class RemoveDependencyVersionTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new RemoveDependencyVersion("org.springframework.restdocs"));
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
				"removes version only for group",
				false,
				"""
				dependencies {
				    testImplementation 'org.springframework.restdocs:spring-restdocs-mockmvc:2.0.6.RELEASE'
				    asciidoctorExt "org.springframework.restdocs:spring-restdocs-asciidoctor:2.0.6.RELEASE"
				    testAnnotationProcessor 'org.projectlombok:lombok:1.18.36'
				    implementation platform('org.springframework.restdocs:spring-restdocs-bom:3.0.0')
				}
				""",
				"""
				dependencies {
				    testImplementation 'org.springframework.restdocs:spring-restdocs-mockmvc'
				    asciidoctorExt "org.springframework.restdocs:spring-restdocs-asciidoctor"
				    testAnnotationProcessor 'org.projectlombok:lombok:1.18.36'
				    implementation platform('org.springframework.restdocs:spring-restdocs-bom:3.0.0')
				}
				"""
			),
			Arguments.of(
				"kotlin dsl",
				true,
				"""
				plugins {
				    java
				}
				dependencies {
				    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc:2.0.6.RELEASE")
				    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")
				    implementation(platform("org.springframework.restdocs:spring-restdocs-bom:3.0.0"))
				}
				""",
				"""
				plugins {
				    java
				}
				dependencies {
				    testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
				    testAnnotationProcessor("org.projectlombok:lombok:1.18.36")
				    implementation(platform("org.springframework.restdocs:spring-restdocs-bom:3.0.0"))
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

	@Test
	void keepsBuildscriptClasspathVersion() {
		rewriteRun((spec) -> spec.recipe(new RemoveDependencyVersion("org.springframework.boot")), buildGradle("""
				buildscript {
				    dependencies {
				        classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.5.16'
				    }
				}
				dependencies {
				    testImplementation 'org.springframework.boot:spring-boot-starter-jdbc-test:4.0.8'
				}
				""", """
				buildscript {
				    dependencies {
				        classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.5.16'
				    }
				}
				dependencies {
				    testImplementation 'org.springframework.boot:spring-boot-starter-jdbc-test'
				}
				"""));
	}

}
