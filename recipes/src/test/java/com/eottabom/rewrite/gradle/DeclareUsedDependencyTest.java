package com.eottabom.rewrite.gradle;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;
import static org.openrewrite.gradle.toolingapi.Assertions.withToolingApi;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;

class DeclareUsedDependencyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // 테스트 JVM 이 JDK 25 라서 JDK 25 를 지원하는 Gradle 9.1 로 모델을 만든다
        // (Gradle 8.14 의 Kotlin DSL 컴파일러는 "25.0.x" 버전 문자열을 못 읽어 build.gradle.kts 테스트가 깨진다)
        spec.beforeRecipe(withToolingApi("9.1.0"))
          .recipe(new DeclareUsedDependency("org.apache.commons.lang3", "org.apache.commons", "commons-lang3", "3.x", null));
    }

    @Test
    void declaresTransitiveDependencyUsedInMain() {
        rewriteRun(
          mavenProject("app",
            // commons-text 가 commons-lang3 를 transitive 로 끌어온다
            buildGradle(
              """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                    implementation 'org.apache.commons:commons-text:1.10.0'
                }
                """,
              spec -> spec.after(actual -> {
                  org.assertj.core.api.Assertions.assertThat(actual)
                    .containsPattern("implementation \"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\"");
                  return actual;
              })
            ),
            srcMainJava(
              java(
                """
                  import org.apache.commons.lang3.StringUtils;
                  class A { String s = StringUtils.trim(" a "); }
                  """
              )
            )
          )
        );
    }

    @Test
    void usesTestImplementationWhenOnlyTestsUseIt() {
        rewriteRun(
          mavenProject("app",
            buildGradle(
              """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                    testImplementation 'org.apache.commons:commons-text:1.10.0'
                }
                """,
              spec -> spec.after(actual -> {
                  org.assertj.core.api.Assertions.assertThat(actual).contains("testImplementation \"org.apache.commons:commons-lang3:");
                  return actual;
              })
            ),
            srcTestJava(
              java(
                """
                  import org.apache.commons.lang3.StringUtils;
                  class ATest { String s = StringUtils.trim(" a "); }
                  """
              )
            )
          )
        );
    }

    @Test
    void noChangeWhenDeclaredOrNotUsed() {
        rewriteRun(
          mavenProject("app",
            buildGradle(
              """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.14.0'
                }
                """
            ),
            srcMainJava(
              java(
                """
                  import org.apache.commons.lang3.StringUtils;
                  class A { String s = StringUtils.trim(" a "); }
                  """
              )
            )
          ),
          mavenProject("other",
            buildGradle(
              """
                plugins { id 'java' }
                repositories { mavenCentral() }
                """
            ),
            srcMainJava(java("class B {}"))
          )
        );
    }

    @Test
    void matchesPackageBeforeUpstreamRename() {
        rewriteRun(
          // 원본이 쓰는 commons-lang 2 는 테스트 classpath 에 없다 (import 텍스트로만 판단하는 것을 검증)
          spec -> spec.typeValidationOptions(org.openrewrite.test.TypeValidation.none()).recipes(
            new DeclareUsedDependency("org.apache.commons.lang3, org.apache.commons.lang", "org.apache.commons", "commons-lang3", null, null),
            new DeclareUsedDependency("org.apache.commons.io", "commons-io", "commons-io", "2.x", null)),
          mavenProject("app",
            buildGradle(
              """
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
              spec -> spec.after(actual -> {
                  org.assertj.core.api.Assertions.assertThat(actual)
                    .contains("implementation \"org.apache.commons:commons-lang3\"")
                    .doesNotContain("commons-io");
                  return actual;
              })
            ),
            srcMainJava(
              java(
                """
                  import org.apache.commons.lang.StringUtils;
                  class A { String s = StringUtils.trim(" a "); }
                  """
              )
            )
          )
        );
    }

    @Test
    void usesSourceSetSpecificConfigurationForTestFixtures() {
        rewriteRun(
          spec -> spec.typeValidationOptions(org.openrewrite.test.TypeValidation.none())
            .recipe(new DeclareUsedDependency("org.apache.commons.io", "commons-io", "commons-io", "2.x", null)),
          mavenProject("app",
            buildGradle(
              """
                plugins {
                    id 'java'
                    id 'java-test-fixtures'
                }
                repositories { mavenCentral() }
                """,
              spec -> spec.after(actual -> {
                  org.assertj.core.api.Assertions.assertThat(actual)
                    .contains("testFixturesImplementation \"commons-io:commons-io:2.")
                    .doesNotContain("testImplementation \"commons-io");
                  return actual;
              })
            ),
            java(
              """
                import org.apache.commons.io.FileUtils;
                class Fixture { Object o = FileUtils.class; }
                """,
              spec -> spec.path("src/testFixtures/java/Fixture.java")
                .markers(org.openrewrite.java.marker.JavaSourceSet.build("testFixtures", java.util.Collections.emptyList()))
            )
          )
        );
    }

    @Test
    void kotlinDsl() {
        rewriteRun(
          mavenProject("app",
            buildGradleKts(
              """
                plugins { java }
                repositories { mavenCentral() }
                dependencies {
                    implementation("org.apache.commons:commons-text:1.10.0")
                }
                """,
              spec -> spec.after(actual -> {
                  org.assertj.core.api.Assertions.assertThat(actual)
                    .containsPattern("implementation\\(\"org.apache.commons:commons-lang3:3\\.\\d+(\\.\\d+)?\"\\)");
                  return actual;
              })
            ),
            srcMainJava(
              java(
                """
                  import org.apache.commons.lang3.StringUtils;
                  class A { String s = StringUtils.trim(" a "); }
                  """
              )
            )
          )
        );
    }
}
