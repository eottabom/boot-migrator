package com.eottabom.rewrite.feign;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class DisambiguateRetryableExceptionNullTests implements RewriteTest {

	private static final String FEIGN_STUB = """
			package feign;
			public class RetryableException extends RuntimeException {
			    public RetryableException(int status, String message, Request.HttpMethod httpMethod, Throwable cause, java.util.Date retryAfter, Request request) {}
			}
			""";

	private static final String REQUEST_STUB = """
			package feign;
			public final class Request {
			    public enum HttpMethod { GET, POST }
			}
			""";

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new DisambiguateRetryableExceptionNull())
			.parser(JavaParser.fromJavaVersion().dependsOn(FEIGN_STUB, REQUEST_STUB));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void rewrites(String scenario, String before, String after) {
		rewriteRun((after != null) ? java(before, after) : java(before));
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"retryAfter 자리의 null 을 (Long) null 로",
				"""
				import feign.Request;
				import feign.RetryableException;
				class Decoder {
				    RuntimeException decode(Request request, Exception e) {
				        return new RetryableException(503, "retry", Request.HttpMethod.GET, e, null, request);
				    }
				}
				""",
				"""
				import feign.Request;
				import feign.RetryableException;
				class Decoder {
				    RuntimeException decode(Request request, Exception e) {
				        return new RetryableException(503, "retry", Request.HttpMethod.GET, e, (Long) null, request);
				    }
				}
				"""
			),
			Arguments.of(
				"null 이 아니면 그대로",
				"""
				import feign.Request;
				import feign.RetryableException;
				class Decoder {
				    RuntimeException decode(Request request, Exception e) {
				        return new RetryableException(503, "retry", Request.HttpMethod.GET, e, new java.util.Date(), request);
				    }
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
