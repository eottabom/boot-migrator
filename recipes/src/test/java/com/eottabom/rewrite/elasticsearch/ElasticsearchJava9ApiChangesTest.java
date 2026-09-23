package com.eottabom.rewrite.elasticsearch;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class ElasticsearchJava9ApiChangesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("com.eottabom.rewrite.elasticsearch.ElasticsearchJava9ApiChanges")
          .typeValidationOptions(TypeValidation.none())
          .parser(JavaParser.fromJavaVersion().dependsOn(
            "package co.elastic.clients.elasticsearch.cat.aliases; public class AliasesRecord { public String index() { return null; } }",
            "package co.elastic.clients.elasticsearch.cat; public class AliasesResponse { public java.util.List<co.elastic.clients.elasticsearch.cat.aliases.AliasesRecord> valueBody() { return null; } }"
          ));
    }

    @Test
    void renamesValueBody() {
        rewriteRun(
          java(
            """
              import co.elastic.clients.elasticsearch.cat.AliasesResponse;

              class Repo {
                  int count(AliasesResponse response) {
                      return response.valueBody().size();
                  }
              }
              """,
            """
              import co.elastic.clients.elasticsearch.cat.AliasesResponse;

              class Repo {
                  int count(AliasesResponse response) {
                      return response.aliases().size();
                  }
              }
              """
          )
        );
    }
}
