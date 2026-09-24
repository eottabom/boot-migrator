package com.eottabom.rewrite.httpclient;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class FixHttpClient5AsyncInterceptorsTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new FixHttpClient5AsyncInterceptors())
			.typeValidationOptions(TypeValidation.none())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn(
						"package org.apache.hc.core5.http; public interface HttpResponse { void addHeader(String n, Object v); }",
						"package org.apache.hc.core5.http; public interface HttpResponseInterceptor { void process(HttpResponse r, Object entity, Object ctx); }",
						"package org.apache.hc.client5.http.impl.async; public class HttpAsyncClientBuilder { public HttpAsyncClientBuilder addInterceptorLast(org.apache.hc.core5.http.HttpResponseInterceptor i) { return this; } }",
						"package org.opensearch.client; public class RestClientBuilder { public interface HttpClientConfigCallback { org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder customizeHttpClient(org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder b); } public RestClientBuilder setHttpClientConfigCallback(HttpClientConfigCallback cb) { return this; } }"));
	}

	@Test
	void renamesInterceptorMethodAndAddsEntityParameter() {
		rewriteRun(java("""
				import org.apache.hc.core5.http.HttpResponseInterceptor;
				import org.opensearch.client.RestClientBuilder;

				class Config {
				    void configure(RestClientBuilder builder) {
				        builder.setHttpClientConfigCallback(httpClientBuilder -> {
				            httpClientBuilder.addInterceptorLast(
				                (HttpResponseInterceptor) (response, context) ->
				                    response.addHeader("X-Elastic-Product", "Elasticsearch"));
				            return httpClientBuilder;
				        });
				    }
				}
				""", """
				import org.apache.hc.core5.http.HttpResponseInterceptor;
				import org.opensearch.client.RestClientBuilder;

				class Config {
				    void configure(RestClientBuilder builder) {
				        builder.setHttpClientConfigCallback(httpClientBuilder -> {
				            httpClientBuilder.addResponseInterceptorLast(
				                (HttpResponseInterceptor) (response, entity, context) ->
				                    response.addHeader("X-Elastic-Product", "Elasticsearch"));
				            return httpClientBuilder;
				        });
				    }
				}
				"""));
	}

}
