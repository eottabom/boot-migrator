package com.eottabom.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RuntimeMigrationRiskTests implements RewriteTest {

	@Test
	void marksCatchAllButLeavesSpecificExceptionHandlerUnchanged() {
		rewriteRun((spec) -> spec.recipeFromResources("com.eottabom.rewrite.spring.FindCatchAllHandlerMasking404")
			.parser(JavaParser.fromJavaVersion().dependsOn("""
					package org.springframework.web.bind.annotation;
					public @interface ExceptionHandler { Class<? extends Throwable>[] value() default {}; }
					""")), java("""
					import org.springframework.web.bind.annotation.ExceptionHandler;

					class Advice {
					    @ExceptionHandler(Exception.class)
					    void all(Exception ex) {}
					    @ExceptionHandler(IllegalArgumentException.class)
					    void specific(IllegalArgumentException ex) {}
					}
					""", """
					import org.springframework.web.bind.annotation.ExceptionHandler;

					class Advice {
					    /*~~>*/@ExceptionHandler(Exception.class)
					    void all(Exception ex) {}
					    @ExceptionHandler(IllegalArgumentException.class)
					    void specific(IllegalArgumentException ex) {}
					}
					"""));
	}

	@Test
	void marksTransactionalEventOnlyWhenTransactionAnnotationIsInSameFile() {
		rewriteRun((spec) -> spec
			.recipeFromResources("com.eottabom.rewrite.spring.FindTransactionalEventListenerConfiguration")
			.parser(JavaParser.fromJavaVersion()
				.dependsOn(
						"package org.springframework.transaction.event; public @interface TransactionalEventListener {}",
						"package org.springframework.transaction.annotation; public @interface Transactional {}")),
				java("""
						import org.springframework.transaction.event.TransactionalEventListener;
						import org.springframework.transaction.annotation.Transactional;

						class Listener {
						    @Transactional
						    @TransactionalEventListener
						    void listen(String event) {}
						}
						""", """
						import org.springframework.transaction.event.TransactionalEventListener;
						import org.springframework.transaction.annotation.Transactional;

						class Listener {
						    @Transactional
						    /*~~>*/@TransactionalEventListener
						    void listen(String event) {}
						}
						"""), java("""
						import org.springframework.transaction.event.TransactionalEventListener;

						class OtherListener {
						    @TransactionalEventListener
						    void listen(String event) {}
						}
						"""));
	}

	@Test
	void conditionalScanNeedsOnlyOneBeanCondition() {
		rewriteRun((spec) -> spec.recipeFromResources("com.eottabom.rewrite.spring.FindLateConditionalComponentScan")
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package org.springframework.context.annotation; public @interface ComponentScan {}",
						"package org.springframework.boot.autoconfigure.condition; public @interface ConditionalOnMissingBean {}")),
				java("""
						import org.springframework.context.annotation.ComponentScan;
						import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

						@ComponentScan
						@ConditionalOnMissingBean
						class Config {}
						""", """
						import org.springframework.context.annotation.ComponentScan;
						import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

						/*~~>*/@ComponentScan
						@ConditionalOnMissingBean
						class Config {}
						"""), java("""
						import org.springframework.context.annotation.ComponentScan;

						@ComponentScan
						class PlainConfig {}
						"""));
	}

	@Test
	void marksRestClientAndRequestFactoryCreation() {
		rewriteRun((spec) -> spec
			.recipeFromResources("com.eottabom.rewrite.httpclient.FindRequestBodyBufferingCompatibilityRisk")
			.parser(JavaParser.fromJavaVersion()
				.dependsOn(
						"package org.springframework.web.client; public class RestTemplate { public RestTemplate() {} }",
						"package org.springframework.http.client; public class SimpleClientHttpRequestFactory {}")),
				java("""
						import org.springframework.http.client.SimpleClientHttpRequestFactory;
						import org.springframework.web.client.RestTemplate;

						class Clients {
						    RestTemplate restTemplate = new RestTemplate();
						    Object factory = new SimpleClientHttpRequestFactory();
						}
						""", """
						import org.springframework.http.client.SimpleClientHttpRequestFactory;
						import org.springframework.web.client.RestTemplate;

						class Clients {
						    RestTemplate restTemplate = /*~~>*/new RestTemplate();
						    Object factory = /*~~>*/new SimpleClientHttpRequestFactory();
						}
						"""));
	}

	@Test
	void marksHttpMessageConverterBeansIncludingSubtypes() {
		rewriteRun((spec) -> spec
			.recipeFromResources("com.eottabom.rewrite.spring.FindUnregisteredHttpMessageConverterBeans")
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package org.springframework.context.annotation; public @interface Bean {}",
						"package org.springframework.http.converter; public interface HttpMessageConverter<T> {}",
						"package com.example; public class JsonConverter implements org.springframework.http.converter.HttpMessageConverter<Object> {}")),
				java("""
						import com.example.JsonConverter;
						import org.springframework.context.annotation.Bean;

						class WebConfig {
						    @Bean
						    JsonConverter jsonConverter() {
						        return new JsonConverter();
						    }

						    JsonConverter notABean() {
						        return new JsonConverter();
						    }
						}
						""", """
						import com.example.JsonConverter;
						import org.springframework.context.annotation.Bean;

						class WebConfig {
						    /*~~>*/@Bean
						    JsonConverter jsonConverter() {
						        return new JsonConverter();
						    }

						    JsonConverter notABean() {
						        return new JsonConverter();
						    }
						}
						"""));
	}

}
