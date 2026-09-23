package com.eottabom.rewrite.querydsl;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;

class QuerydslJakartaClassifierTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new QuerydslJakartaClassifier());
    }

    @Test
    void gstringVersionWithJpaClassifier() {
        rewriteRun(
          buildGradle(
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
          )
        );
    }

    @Test
    void literalWithAndWithoutVersion() {
        rewriteRun(
          buildGradle(
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
          )
        );
    }

    @Test
    void leavesAlreadyMigratedAndOtherArtifactsAlone() {
        rewriteRun(
          buildGradle(
            """
              def querydslVersion = '5.0.0'
              dependencies {
                  implementation "com.querydsl:querydsl-jpa:${querydslVersion}:jakarta"
                  implementation "com.querydsl:querydsl-sql:${querydslVersion}"
                  implementation "com.querydsl:querydsl-core"
                  annotationProcessor "com.querydsl:querydsl-apt:5.1.0:general"
              }
              """
          )
        );
    }
}
