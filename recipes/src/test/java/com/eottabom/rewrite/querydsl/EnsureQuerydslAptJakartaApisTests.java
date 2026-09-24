package com.eottabom.rewrite.querydsl;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class EnsureQuerydslAptJakartaApisTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new EnsureQuerydslAptJakartaApis());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void rewrites(String scenario, String before, String after) {
		rewriteRun((after != null) ? buildGradle(before, after) : buildGradle(before));
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"adds missing apis above",
				"""
				def queryDslVersion = '5.0.0'
				dependencies {
				    implementation "io.micrometer:micrometer-registry-datadog"

				    annotationProcessor("com.querydsl:querydsl-apt:${queryDslVersion}:jakarta") // querydsl
				    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

				    implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
				}
				""",
				"""
				def queryDslVersion = '5.0.0'
				dependencies {
				    implementation "io.micrometer:micrometer-registry-datadog"

				    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
				    annotationProcessor("com.querydsl:querydsl-apt:${queryDslVersion}:jakarta") // querydsl
				    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

				    implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
				}
				"""
			),
			Arguments.of(
				"adds both when apt is last statement",
				"""
				dependencies {
				    implementation "com.querydsl:querydsl-jpa:5.1.0:jakarta"
				    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'
				}
				""",
				"""
				dependencies {
				    implementation "com.querydsl:querydsl-jpa:5.1.0:jakarta"
				    annotationProcessor 'jakarta.annotation:jakarta.annotation-api'
				    annotationProcessor 'jakarta.persistence:jakarta.persistence-api'
				    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'
				}
				"""
			)
		);
	}
	// @formatter:on

	@Test
	void noChangeWhenAlreadyPresentOrNotJakarta() {
		rewriteRun(buildGradle("""
				dependencies {
				    annotationProcessor "com.querydsl:querydsl-apt:5.1.0:jakarta"
				    annotationProcessor "jakarta.annotation:jakarta.annotation-api"
				    annotationProcessor "jakarta.persistence:jakarta.persistence-api"
				}
				"""), buildGradle("""
				dependencies {
				    annotationProcessor "com.querydsl:querydsl-apt:5.0.0:jpa"
				}
				""", (spec) -> spec.path("legacy/build.gradle")));
	}

}
