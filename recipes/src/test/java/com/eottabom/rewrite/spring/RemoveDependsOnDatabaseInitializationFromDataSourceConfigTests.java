package com.eottabom.rewrite.spring;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveDependsOnDatabaseInitializationFromDataSourceConfigTest implements RewriteTest {

    private static final String BEAN = """
      package org.springframework.context.annotation;
      public @interface Bean {}
      """;
    private static final String DEPENDS_ON = """
      package org.springframework.boot.sql.init.dependency;
      public @interface DependsOnDatabaseInitialization {}
      """;
    private static final String HIKARI_CONFIG = """
      package com.zaxxer.hikari;
      public class HikariConfig {
          public void setDataSource(javax.sql.DataSource ds) {}
      }
      """;
    private static final String REPO = """
      package com.example;
      public class JdbcRepo {
          public JdbcRepo(javax.sql.DataSource ds) {}
      }
      """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveDependsOnDatabaseInitializationFromDataSourceConfig())
          .parser(JavaParser.fromJavaVersion().dependsOn(BEAN, DEPENDS_ON, HIKARI_CONFIG, REPO));
    }

    @Test
    void removesFromHikariConfigBeanOnly() {
        rewriteRun(
          java(
            """
              import com.example.JdbcRepo;
              import com.zaxxer.hikari.HikariConfig;
              import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
              import org.springframework.context.annotation.Bean;

              class Config {
                  @Bean
                  @DependsOnDatabaseInitialization
                  public HikariConfig hikariConfig() {
                      return new HikariConfig();
                  }

                  @Bean
                  @DependsOnDatabaseInitialization
                  public JdbcRepo jdbcRepo(javax.sql.DataSource ds) {
                      return new JdbcRepo(ds);
                  }
              }
              """,
            """
              import com.example.JdbcRepo;
              import com.zaxxer.hikari.HikariConfig;
              import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
              import org.springframework.context.annotation.Bean;

              class Config {
                  @Bean
                  public HikariConfig hikariConfig() {
                      return new HikariConfig();
                  }

                  @Bean
                  @DependsOnDatabaseInitialization
                  public JdbcRepo jdbcRepo(javax.sql.DataSource ds) {
                      return new JdbcRepo(ds);
                  }
              }
              """
          )
        );
    }
}
