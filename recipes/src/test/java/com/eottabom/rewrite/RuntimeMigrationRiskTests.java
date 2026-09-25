package com.eottabom.rewrite;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.openrewrite.java.Assertions.java;

class RuntimeMigrationRiskTests implements RewriteTest {

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void marksReviewCandidates(String scenario, String recipe, String[] stubs, SourceSpecs[] sources) {
		rewriteRun((spec) -> spec.recipeFromResources(recipe).parser(JavaParser.fromJavaVersion().dependsOn(stubs)),
				sources);
	}

	private static Arguments scenario(String scenario, String recipe, String[] stubs, SourceSpecs... sources) {
		return Arguments.of(scenario, recipe, stubs, sources);
	}

	private static String[] stubs(String... stubs) {
		return stubs;
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			scenario(
				"Exception 전역 처리기만 표시하고 구체적인 예외 처리기는 그대로",
				"com.eottabom.rewrite.spring.FindCatchAllHandlerMasking404",
				stubs("""
					package org.springframework.web.bind.annotation;
					public @interface ExceptionHandler { Class<? extends Throwable>[] value() default {}; }
					"""),
				java("""
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
					""")
			),
			scenario(
				"트랜잭션 이벤트 리스너는 같은 파일에 Transactional 이 있을 때만 표시",
				"com.eottabom.rewrite.spring.FindTransactionalEventListenerConfiguration",
				stubs("package org.springframework.transaction.event; public @interface TransactionalEventListener {}",
					"package org.springframework.transaction.annotation; public @interface Transactional {}"),
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
					"""),
				java("""
					import org.springframework.transaction.event.TransactionalEventListener;

					class OtherListener {
					    @TransactionalEventListener
					    void listen(String event) {}
					}
					""")
			),
			scenario(
				"ComponentScan 은 같은 파일에 빈 조건이 하나라도 있을 때 표시",
				"com.eottabom.rewrite.spring.FindLateConditionalComponentScan",
				stubs("package org.springframework.context.annotation; public @interface ComponentScan {}",
					"package org.springframework.boot.autoconfigure.condition; public @interface ConditionalOnMissingBean {}"),
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
					"""),
				java("""
					import org.springframework.context.annotation.ComponentScan;

					@ComponentScan
					class PlainConfig {}
					""")
			),
			scenario(
				"RestTemplate 과 요청 팩토리 생성 위치 표시",
				"com.eottabom.rewrite.httpclient.FindRequestBodyBufferingCompatibilityRisk",
				stubs("package org.springframework.web.client; public class RestTemplate { public RestTemplate() {} }",
					"package org.springframework.http.client; public class SimpleClientHttpRequestFactory {}"),
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
					""")
			),
			scenario(
				"HttpMessageConverter 하위 타입을 반환하는 @Bean 만 표시",
				"com.eottabom.rewrite.spring.FindUnregisteredHttpMessageConverterBeans",
				stubs("package org.springframework.context.annotation; public @interface Bean {}",
					"package org.springframework.http.converter; public interface HttpMessageConverter<T> {}",
					"package com.example; public class JsonConverter implements org.springframework.http.converter.HttpMessageConverter<Object> {}"),
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
					""")
			)
		);
	}
	// @formatter:on

}
