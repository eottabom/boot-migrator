package com.eottabom.rewrite.feign;

import org.junit.jupiter.api.Test;
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

	@Test
	void castsNullRetryAfter() {
		rewriteRun(java("""
				import feign.Request;
				import feign.RetryableException;
				class Decoder {
				    RuntimeException decode(Request request, Exception e) {
				        return new RetryableException(503, "retry", Request.HttpMethod.GET, e, null, request);
				    }
				}
				""", """
				import feign.Request;
				import feign.RetryableException;
				class Decoder {
				    RuntimeException decode(Request request, Exception e) {
				        return new RetryableException(503, "retry", Request.HttpMethod.GET, e, (Long) null, request);
				    }
				}
				"""));
	}

	@Test
	void leavesNonNullAlone() {
		rewriteRun(
				java("""
						import feign.Request;
						import feign.RetryableException;
						class Decoder {
						    RuntimeException decode(Request request, Exception e) {
						        return new RetryableException(503, "retry", Request.HttpMethod.GET, e, new java.util.Date(), request);
						    }
						}
						"""));
	}

}
