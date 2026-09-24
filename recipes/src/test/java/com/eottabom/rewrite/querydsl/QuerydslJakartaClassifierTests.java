package com.eottabom.rewrite.querydsl;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class QuerydslJakartaClassifierTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new QuerydslJakartaClassifier());
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
				"gstring version with jpa classifier",
				"""
				def queryDslVersion = '5.0.0'
				dependencies {
				    annotationProcessor("com.querydsl:querydsl-apt:${queryDslVersion}:jpa")
				    implementation "com.querydsl:querydsl-jpa:${queryDslVersion}"
				}
				""",
				"""
				def queryDslVersion = '5.0.0'
				dependencies {
				    annotationProcessor("com.querydsl:querydsl-apt:${queryDslVersion}:jakarta")
				    implementation "com.querydsl:querydsl-jpa:${queryDslVersion}:jakarta"
				}
				"""
			),
			Arguments.of(
				"literal with and without version",
				"""
				dependencies {
				    implementation "com.querydsl:querydsl-jpa"
				    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jpa'
				}
				""",
				"""
				dependencies {
				    implementation "com.querydsl:querydsl-jpa::jakarta"
				    annotationProcessor 'com.querydsl:querydsl-apt:5.1.0:jakarta'
				}
				"""
			),
			Arguments.of(
				"leaves already migrated and other artifacts alone",
				"""
				def querydslVersion = '5.0.0'
				dependencies {
				    implementation "com.querydsl:querydsl-jpa:${querydslVersion}:jakarta"
				    implementation "com.querydsl:querydsl-sql:${querydslVersion}"
				    implementation "com.querydsl:querydsl-core"
				    annotationProcessor "com.querydsl:querydsl-apt:5.1.0:general"
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
