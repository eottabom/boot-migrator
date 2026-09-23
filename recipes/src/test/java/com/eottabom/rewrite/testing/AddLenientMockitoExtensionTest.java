package com.eottabom.rewrite.testing;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class AddLenientMockitoExtensionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddLenientMockitoExtension())
          .typeValidationOptions(TypeValidation.none())
          .parser(JavaParser.fromJavaVersion().dependsOn(
            "package org.mockito; public @interface Mock {}",
            "package org.mockito; public @interface InjectMocks {}",
            "package org.junit.jupiter.api; public @interface Test {}",
            "package org.junit.jupiter.api.extension; public @interface ExtendWith { Class<?>[] value(); }",
            "package org.mockito.junit.jupiter; public class MockitoExtension {}"
          ));
    }

    @Test
    void addsLenientExtensionWhenMissing() {
        rewriteRun(
          java(
            """
              import org.junit.jupiter.api.Test;
              import org.mockito.InjectMocks;
              import org.mockito.Mock;

              class ServiceTest {
                  @Mock
                  Object repository;

                  @InjectMocks
                  Object service;

                  @Test
                  void test() {}
              }
              """,
            """
              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.api.extension.ExtendWith;
              import org.mockito.InjectMocks;
              import org.mockito.Mock;
              import org.mockito.junit.jupiter.MockitoExtension;
              import org.mockito.junit.jupiter.MockitoSettings;
              import org.mockito.quality.Strictness;

              @ExtendWith(MockitoExtension.class)
              @MockitoSettings(strictness = Strictness.LENIENT)
              class ServiceTest {
                  @Mock
                  Object repository;

                  @InjectMocks
                  Object service;

                  @Test
                  void test() {}
              }
              """
          )
        );
    }

    @Test
    void leavesExistingExtendWithAlone() {
        rewriteRun(
          java(
            """
              import org.junit.jupiter.api.Test;
              import org.junit.jupiter.api.extension.ExtendWith;
              import org.mockito.Mock;
              import org.mockito.junit.jupiter.MockitoExtension;

              @ExtendWith(MockitoExtension.class)
              class StrictTest {
                  @Mock
                  Object repository;

                  @Test
                  void test() {}
              }
              """
          )
        );
    }
}
