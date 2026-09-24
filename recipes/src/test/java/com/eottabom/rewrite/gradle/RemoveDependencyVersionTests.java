package com.eottabom.rewrite.gradle;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class RemoveDependencyVersionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveDependencyVersion("org.springframework.restdocs"));
    }

    @Test
    void removesVersionOnlyForGroup() {
        rewriteRun(
          buildGradle(
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
          )
        );
    }

    @Test
    void keepsBuildscriptClasspathVersion() {
        rewriteRun(
          spec -> spec.recipe(new RemoveDependencyVersion("org.springframework.boot")),
          buildGradle(
            """
              buildscript {
                  dependencies {
                      classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.5.16'
                  }
              }
              dependencies {
                  testImplementation 'org.springframework.boot:spring-boot-starter-jdbc-test:4.0.8'
              }
              """,
            """
              buildscript {
                  dependencies {
                      classpath 'org.springframework.boot:spring-boot-gradle-plugin:3.5.16'
                  }
              }
              dependencies {
                  testImplementation 'org.springframework.boot:spring-boot-starter-jdbc-test'
              }
              """
          )
        );
    }

    @Test
    void kotlinDsl() {
        rewriteRun(
          buildGradleKts(
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
}
