package com.eottabom.rewrite.httpclient;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.ReorderMethodArguments;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class RevertHttpClient5ForElasticsearchRestClientTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RevertHttpClient5ForElasticsearchRestClient())
          .typeValidationOptions(TypeValidation.none())
          .parser(JavaParser.fromJavaVersion().dependsOn(
            // HttpClient 5 (upstream 이 바꾼 결과)
            "package org.apache.hc.core5.http; public class HttpHost { public HttpHost(String scheme, String hostname, int port) {} }",
            "package org.apache.hc.client5.http.auth; public class AuthScope { public AuthScope(String host, int port) {} }",
            "package org.apache.hc.client5.http.auth; public class UsernamePasswordCredentials { public UsernamePasswordCredentials(String u, char[] p) {} }",
            "package org.apache.hc.client5.http.auth; public interface CredentialsStore { void setCredentials(AuthScope s, UsernamePasswordCredentials c); }",
            "package org.apache.hc.client5.http.impl.auth; public class BasicCredentialsProvider implements org.apache.hc.client5.http.auth.CredentialsStore { public void setCredentials(org.apache.hc.client5.http.auth.AuthScope s, org.apache.hc.client5.http.auth.UsernamePasswordCredentials c) {} }",
            // HttpClient 4 (RestClient 가 쓰는 것)
            "package org.apache.http; public class HttpHost { public HttpHost(String hostname, int port, String scheme) {} }",
            "package org.apache.http.auth; public class AuthScope { public static final AuthScope ANY = null; }",
            "package org.apache.http.auth; public class UsernamePasswordCredentials { public UsernamePasswordCredentials(String u, String p) {} }",
            "package org.apache.http.client; public interface CredentialsProvider {}",
            "package org.apache.http.impl.client; public class BasicCredentialsProvider {}",
            "package org.elasticsearch.client; public class RestClient { public static Object builder(org.apache.http.HttpHost... h) { return null; } }"
          ));
    }

    @Test
    void revertsRestClientSetup() {
        rewriteRun(
          java(
            """
              import org.apache.hc.client5.http.auth.AuthScope;
              import org.apache.hc.client5.http.auth.CredentialsStore;
              import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
              import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
              import org.apache.hc.core5.http.HttpHost;
              import org.elasticsearch.client.RestClient;

              class Config {
                  Object client(String user, String password, String scheme, String host, int port) {
                      CredentialsStore credentialsProvider = new BasicCredentialsProvider();
                      credentialsProvider.setCredentials(new AuthScope(null, -1), new UsernamePasswordCredentials(user, password.toCharArray()));
                      return RestClient.builder(new HttpHost(scheme, host, port));
                  }
              }
              """,
            """
              import org.apache.http.HttpHost;
              import org.apache.http.auth.AuthScope;
              import org.apache.http.auth.UsernamePasswordCredentials;
              import org.apache.http.client.CredentialsProvider;
              import org.apache.http.impl.client.BasicCredentialsProvider;
              import org.elasticsearch.client.RestClient;

              class Config {
                  Object client(String user, String password, String scheme, String host, int port) {
                      CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                      credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user, password));
                      return RestClient.builder(new HttpHost(host, port, scheme));
                  }
              }
              """
          )
        );
    }

    @Test
    void upstreamCanConvertHttpHostAgainAfterRevert() {
        // 4.0 단계 실행 안에서: 3.0 체인의 되돌리기 뒤에 upstream HttpClient 5 전환이 다시 돈다 (upstream 과 같은 설정)
        rewriteRun(
          spec -> spec.recipes(
            new RevertHttpClient5ForElasticsearchRestClient(),
            new ChangeType("org.apache.http.HttpHost", "org.apache.hc.core5.http.HttpHost", true),
            new ReorderMethodArguments("org.apache.hc.core5.http.HttpHost <constructor>(java.lang.String, int, java.lang.String)",
              new String[]{"scheme", "hostname", "port"}, new String[]{"hostname", "port", "scheme"}, null, null))
            .cycles(1).expectedCyclesThatMakeChanges(1),
          java(
            """
              import org.apache.hc.client5.http.auth.AuthScope;
              import org.apache.hc.core5.http.HttpHost;
              import org.elasticsearch.client.RestClient;

              class Config {
                  Object scope = new AuthScope(null, -1);

                  Object client(String scheme, String host, int port) {
                      return RestClient.builder(new HttpHost(scheme, host, port));
                  }
              }
              """,
            // AuthScope 는 되돌린 채로 남고, HttpHost 는 다시 HttpClient 5 순서 (scheme, host, port)
            """
              import org.apache.hc.core5.http.HttpHost;
              import org.apache.http.auth.AuthScope;
              import org.elasticsearch.client.RestClient;

              class Config {
                  Object scope = AuthScope.ANY;

                  Object client(String scheme, String host, int port) {
                      return RestClient.builder(new HttpHost(scheme, host, port));
                  }
              }
              """
          )
        );
    }

    @Test
    void leavesFilesWithoutRestClientAlone() {
        rewriteRun(
          java(
            """
              import org.apache.hc.core5.http.HttpHost;

              class Other {
                  Object host(String scheme, String host, int port) {
                      return new HttpHost(scheme, host, port);
                  }
              }
              """
          )
        );
    }
}
