package com.eottabom.rewrite.hibernate;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class ReplaceAnnotationsQueryHintsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("com.eottabom.rewrite.hibernate.ReplaceAnnotationsQueryHints")
          .typeValidationOptions(TypeValidation.none())
          .parser(JavaParser.fromJavaVersion().dependsOn(
            "package org.hibernate.annotations; public class QueryHints { public static final String READ_ONLY = \"org.hibernate.readOnly\"; public static final String FETCH_SIZE = \"org.hibernate.fetchSize\"; }",
            "package org.hibernate.jpa; public interface HibernateHints { String HINT_READ_ONLY = \"org.hibernate.readOnly\"; String HINT_FETCH_SIZE = \"org.hibernate.fetchSize\"; }"
          ));
    }

    @Test
    void replacesStaticImportAndQualifiedUse() {
        rewriteRun(
          java(
            """
              import org.hibernate.annotations.QueryHints;

              import static org.hibernate.annotations.QueryHints.READ_ONLY;

              class Repo {
                  String[] hints = {READ_ONLY, QueryHints.FETCH_SIZE};
              }
              """,
            """
              import org.hibernate.jpa.HibernateHints;

              import static org.hibernate.jpa.HibernateHints.HINT_READ_ONLY;

              class Repo {
                  String[] hints = {HINT_READ_ONLY, HibernateHints.HINT_FETCH_SIZE};
              }
              """
          )
        );
    }
}
