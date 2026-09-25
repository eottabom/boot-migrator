package com.eottabom.rewrite.gradle;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.toml.Assertions.toml;

class DeclareAddedDependenciesInVersionCatalogTests implements RewriteTest {

	private static final String CATALOG = """
			[versions]
			spring-boot = "4.0.7"

			[libraries]
			spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }

			[plugins]
			spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		// 앞선 레시피가 의존성을 추가한 상황: 자리표시 좌표를 이번 실행에서 새 좌표로 바꾼다
		spec.recipes(new AddDependencyByRenaming(), new DeclareAddedDependenciesInVersionCatalog());
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void movesAddedDependencyToCatalog(String scenario, SourceSpecs buildScript, String catalogAfter) {
		SourceSpecs catalog = (catalogAfter != null)
				? toml(CATALOG, catalogAfter, (spec) -> spec.path("gradle/libs.versions.toml"))
				: toml(CATALOG, (spec) -> spec.path("gradle/libs.versions.toml"));
		// catalog 항목은 빌드 스크립트를 바꾼 다음 사이클에서 더해진다
		rewriteRun((spec) -> spec.expectedCyclesThatMakeChanges((catalogAfter != null) ? 2 : 1), buildScript, catalog);
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		String catalogWithRestclient = """
			[versions]
			spring-boot = "4.0.7"

			[libraries]
			spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
			spring-boot-restclient = { module = "org.springframework.boot:spring-boot-restclient" }

			[plugins]
			spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
			""";
		return Stream.of(
			Arguments.of("Groovy DSL",
				buildGradle("""
					dependencies {
					    implementation "org.apache.commons:commons-lang3:3.17.0"
					    testImplementation libs.spring.boot.starter.test
					    testImplementation "org.example:placeholder"
					}
					""", """
					dependencies {
					    implementation "org.apache.commons:commons-lang3:3.17.0"
					    testImplementation libs.spring.boot.starter.test
					    testImplementation libs.spring.boot.restclient
					}
					"""),
				catalogWithRestclient),
			Arguments.of("Kotlin DSL",
				buildGradleKts("""
					dependencies {
					    testImplementation(libs.spring.boot.starter.test)
					    testImplementation("org.example:placeholder")
					}
					""", """
					dependencies {
					    testImplementation(libs.spring.boot.starter.test)
					    testImplementation(libs.spring.boot.restclient)
					}
					"""),
				catalogWithRestclient),
			Arguments.of("catalog 를 쓰지 않는 빌드 스크립트는 문자열 그대로",
				buildGradle("""
					dependencies {
					    testImplementation "org.example:placeholder"
					}
					""", """
					dependencies {
					    testImplementation "org.springframework.boot:spring-boot-restclient"
					}
					"""),
				null)
		);
	}
	// @formatter:on

	/**
	 * 테스트 전용. org.example:placeholder 를 spring-boot-restclient 로 바꿔 "이번 실행에서 추가된 의존성" 을
	 * 만든다
	 */
	static class AddDependencyByRenaming extends Recipe {

		@Override
		public String getDisplayName() {
			return "의존성 추가 흉내";
		}

		@Override
		public String getDescription() {
			return "자리표시 좌표를 spring-boot-restclient 로 바꾼다.";
		}

		@Override
		public TreeVisitor<?, ExecutionContext> getVisitor() {
			return new JavaIsoVisitor<>() {
				@Override
				public J.Literal visitLiteral(J.Literal literal, ExecutionContext ctx) {
					if (!"org.example:placeholder".equals(literal.getValue())) {
						return literal;
					}
					String coordinates = "org.springframework.boot:spring-boot-restclient";
					return literal.withValue(coordinates)
						.withValueSource(
								literal.getValueSource().charAt(0) + coordinates + literal.getValueSource().charAt(0));
				}
			};
		}

	}

}
