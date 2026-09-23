package com.eottabom.rewrite.lombok;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class CopyJacksonAnnotationsToAccessorsTest implements RewriteTest {

    private static final String DTO = """
      import com.fasterxml.jackson.annotation.JsonProperty;
      import lombok.Data;

      @Data
      class Response {
          @JsonProperty("isShow")
          private boolean isShow;
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new CopyJacksonAnnotationsToAccessors())
          .parser(JavaParser.fromJavaVersion().dependsOn(
            "package lombok; public @interface Data {}",
            "package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }"
          ));
    }

    @Test
    void createsConfig() {
        rewriteRun(
          java(DTO),
          text(
            null,
            """
              lombok.copyJacksonAnnotationsToAccessors = true
              """,
            spec -> spec.path("lombok.config")
          )
        );
    }

    @Test
    void appendsToExistingConfig() {
        rewriteRun(
          java(DTO),
          text(
            "lombok.addLombokGeneratedAnnotation = true",
            """
              lombok.addLombokGeneratedAnnotation = true
              lombok.copyJacksonAnnotationsToAccessors = true
              """,
            spec -> spec.path("lombok.config")
          )
        );
    }

    @Test
    void keepsExistingKey() {
        rewriteRun(
          java(DTO),
          text(
            "lombok.copyJacksonAnnotationsToAccessors = false\n",
            spec -> spec.path("lombok.config")
          )
        );
    }

    @Test
    void unignoresRootConfig() {
        rewriteRun(
          java(DTO),
          text(
            "lombok.copyJacksonAnnotationsToAccessors = true\n",
            spec -> spec.path("lombok.config")
          ),
          text(
            """
              .idea/
              lombok.config
              """,
            """
              .idea/
              lombok.config
              # 루트 lombok.config 는 커밋한다 (lombok.copyJacksonAnnotationsToAccessors)
              !/lombok.config
              """,
            spec -> spec.path(".gitignore")
          )
        );
    }

    @Test
    void leavesGitignoreWithoutLombokConfig() {
        rewriteRun(
          java(DTO),
          text(
            "lombok.copyJacksonAnnotationsToAccessors = true\n",
            spec -> spec.path("lombok.config")
          ),
          text(
            ".idea/\n",
            spec -> spec.path(".gitignore")
          )
        );
    }

    @Test
    void noJacksonAnnotationNoChange() {
        rewriteRun(
          java(
            """
              import lombok.Data;

              @Data
              class Plain {
                  private boolean isShow;
              }
              """
          )
        );
    }
}
