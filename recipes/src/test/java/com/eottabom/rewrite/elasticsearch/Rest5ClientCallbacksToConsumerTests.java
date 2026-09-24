package com.eottabom.rewrite.elasticsearch;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class Rest5ClientCallbacksToConsumerTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new Rest5ClientCallbacksToConsumer())
			.typeValidationOptions(TypeValidation.none())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn(
						"package org.apache.hc.client5.http.impl.async; public class HttpAsyncClientBuilder { public HttpAsyncClientBuilder setDefaultCredentialsProvider(Object p) { return this; } }",
						// 원본은 8.x RestClientBuilder 기준으로 타입이 붙는다 (콜백이 builder 를 돌려주는 형태)
						"""
								package org.elasticsearch.client;
								import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
								public class RestClientBuilder {
								    public interface HttpClientConfigCallback { HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder b); }
								    public RestClientBuilder setHttpClientConfigCallback(HttpClientConfigCallback cb) { return this; }
								    public RestClient build() { return null; }
								}
								""",
						"package org.elasticsearch.client; public class RestClient { public static RestClientBuilder builder(Object... h) { return null; } }"));
	}

	@Test
	void removesTrailingReturnOfBuilder() {
		rewriteRun(java("""
				import org.elasticsearch.client.RestClient;

				class Config {
				    RestClient client(Object host, Object credentials) {
				        return RestClient.builder(host)
				            .setHttpClientConfigCallback(httpClientBuilder -> {
				                httpClientBuilder.setDefaultCredentialsProvider(credentials);
				                return httpClientBuilder;
				            })
				            .build();
				    }
				}
				""", """
				import org.elasticsearch.client.RestClient;

				class Config {
				    RestClient client(Object host, Object credentials) {
				        return RestClient.builder(host)
				            .setHttpClientConfigCallback(httpClientBuilder -> {
				                httpClientBuilder.setDefaultCredentialsProvider(credentials);
				            })
				            .build();
				    }
				}
				"""));
	}

	@Test
	void keepsCallWhenReturningInvocation() {
		rewriteRun(java("""
				import org.elasticsearch.client.RestClient;

				class Config {
				    RestClient client(Object host, Object credentials) {
				        return RestClient.builder(host)
				            .setHttpClientConfigCallback(b -> {
				                return b.setDefaultCredentialsProvider(credentials);
				            })
				            .build();
				    }
				}
				""", """
				import org.elasticsearch.client.RestClient;

				class Config {
				    RestClient client(Object host, Object credentials) {
				        return RestClient.builder(host)
				            .setHttpClientConfigCallback(b -> {
				                b.setDefaultCredentialsProvider(credentials);
				            })
				            .build();
				    }
				}
				"""));
	}

	@Test
	void leavesExpressionLambda() {
		rewriteRun(java("""
				import org.elasticsearch.client.RestClient;

				class Config {
				    RestClient client(Object host, Object credentials) {
				        return RestClient.builder(host)
				            .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(credentials))
				            .build();
				    }
				}
				"""));
	}

}
