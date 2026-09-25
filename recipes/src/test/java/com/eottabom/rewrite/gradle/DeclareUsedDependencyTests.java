package com.eottabom.rewrite.gradle;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.toolingapi.Assertions.withToolingApi;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;

class DeclareUsedDependencyTests implements RewriteTest {

	private static final String USES_LANG3 = """
			import org.apache.commons.lang3.StringUtils;
			class A { String s = StringUtils.trim(" a "); }
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		// 테스트 JVM 이 JDK 25 라서 JDK 25 를 지원하는 Gradle 9.1 로 모델을 만든다
		// (Gradle 8.14 의 Kotlin DSL 컴파일러는 "25.0.x" 버전 문자열을 못 읽어 build.gradle.kts 테스트가
		// 깨진다)
		spec.beforeRecipe(withToolingApi("9.1.0"))
			.recipe(new DeclareUsedDependency("org.apache.commons.lang3", "org.apache.commons", "commons-lang3", "3.x",
					null));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("transitiveOnly")
	void declaresDependencyThatWasOnlyTransitive(String scenario, SourceSpecs buildScript, SourceSpecs source) {
		rewriteRun(mavenProject("app", buildScript, source));
	}

	// @formatter:off
	static Stream<Arguments> transitiveOnly() {
		// commons-text 가 commons-lang3 를 transitive 로 끌어온다
		return Stream.of(
			Arguments.of("main 에서 쓰면 implementation",
				buildGradle("""
					plugins { id 'java' }
					repositories { mavenCentral() }
					dependencies {
					    implementation 'org.apache.commons:commons-text:1.10.0'
					}
					""", (spec) -> spec.after(expect((a) -> a
						.containsPattern("implementation \"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\"")))),
				srcMainJava(java(USES_LANG3))),
			Arguments.of("테스트에서만 쓰면 testImplementation",
				buildGradle("""
					plugins { id 'java' }
					repositories { mavenCentral() }
					dependencies {
					    testImplementation 'org.apache.commons:commons-text:1.10.0'
					}
					""", (spec) -> spec.after(expect((a) -> a
						.contains("testImplementation \"org.apache.commons:commons-lang3:")))),
				srcTestJava(java(USES_LANG3))),
			Arguments.of("Kotlin DSL",
				buildGradleKts("""
					plugins { java }
					repositories { mavenCentral() }
					dependencies {
					    implementation("org.apache.commons:commons-text:1.10.0")
					}
					""", (spec) -> spec.after(expect((a) -> a
						.containsPattern("implementation\\(\"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\"\\)")))),
				srcMainJava(java(USES_LANG3)))
		);
	}
	// @formatter:on

	@Test
	void leavesDeclaredOrUnusedDependency() {
		rewriteRun(mavenProject("app", buildGradle("""
				plugins { id 'java' }
				repositories { mavenCentral() }
				dependencies {
				    implementation 'org.apache.commons:commons-lang3:3.14.0'
				}
				"""), srcMainJava(java(USES_LANG3))), mavenProject("other", buildGradle("""
				plugins { id 'java' }
				repositories { mavenCentral() }
				"""), srcMainJava(java("class B {}"))));
	}

	@Test
	void matchesPackageBeforeUpstreamRename() {
		rewriteRun(
				// 원본이 쓰는 commons-lang 2 는 테스트 classpath 에 없다 (import 텍스트로만 판단하는 것을 검증)
				(spec) -> spec.typeValidationOptions(TypeValidation.none())
					.recipes(
							new DeclareUsedDependency("org.apache.commons.lang3, org.apache.commons.lang",
									"org.apache.commons", "commons-lang3", null, null),
							new DeclareUsedDependency("org.apache.commons.io", "commons-io", "commons-io", "2.x",
									null)),
				mavenProject("app",
						buildGradle("""
								plugins {
								    id 'java'
								    id 'org.springframework.boot' version '3.0.13'
								    id 'io.spring.dependency-management' version '1.1.7'
								}
								repositories { mavenCentral() }
								dependencies {
								    implementation 'org.springframework.boot:spring-boot-starter'
								}
								""",
								(spec) -> spec.after(
										expect((a) -> a.contains("implementation \"org.apache.commons:commons-lang3\"")
											.doesNotContain("commons-io")))),
						srcMainJava(java("""
								import org.apache.commons.lang.StringUtils;
								class A { String s = StringUtils.trim(" a "); }
								"""))));
	}

	@Test
	void usesSourceSetConfigurationForTestFixtures() {
		rewriteRun(
				(spec) -> spec.typeValidationOptions(TypeValidation.none())
					.recipe(new DeclareUsedDependency("org.apache.commons.io", "commons-io", "commons-io", "2.x",
							null)),
				mavenProject("app", buildGradle("""
						plugins {
						    id 'java'
						    id 'java-test-fixtures'
						}
						repositories { mavenCentral() }
						""",
						(spec) -> spec
							.after(expect((a) -> a.contains("testFixturesImplementation \"commons-io:commons-io:2.")
								.doesNotContain("testImplementation \"commons-io")))),
						java("""
								import org.apache.commons.io.FileUtils;
								class Fixture { Object o = FileUtils.class; }
								""", (spec) -> spec.path("src/testFixtures/java/Fixture.java")
							.markers(JavaSourceSet.build("testFixtures", List.of())))));
	}

	/** 결과 버전이 저장소 최신을 따라가서 문자열 대신 검증식으로 확인한다 */
	private static UnaryOperator<String> expect(Consumer<AbstractStringAssert<?>> check) {
		return (actual) -> {
			check.accept(assertThat(actual));
			return actual;
		};
	}

}
