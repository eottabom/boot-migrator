package com.eottabom.rewrite.gradle;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.buildGradleKts;

class UpgradeJacocoToolVersionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new UpgradeJacocoToolVersion("0.8.15"));
    }

    @Test
    void upgradesOldToolVersionOnlyInJacocoBlock() {
        rewriteRun(
          buildGradle(
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
          )
        );
    }

    @Test
    void keepsNewerVersion() {
        rewriteRun(
          buildGradle(
            """
              jacoco {
                  toolVersion = '0.8.16'
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
}
