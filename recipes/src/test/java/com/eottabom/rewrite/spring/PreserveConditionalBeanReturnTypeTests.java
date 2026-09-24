package com.eottabom.rewrite.spring;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class PreserveConditionalBeanReturnTypeTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new PreserveConditionalBeanReturnType())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package org.springframework.context.annotation; public @interface Bean {}", """
						package org.springframework.boot.autoconfigure.condition;
						import java.lang.annotation.Annotation;
						public @interface ConditionalOnMissingBean {
						    Class<?>[] value() default {};
						    String[] type() default {};
						    String[] name() default {};
						    Class<? extends Annotation>[] annotation() default {};
						}
						""", """
						package org.springframework.boot.autoconfigure.condition;
						import java.lang.annotation.Annotation;
						public @interface ConditionalOnBean {
						    Class<?>[] value() default {};
						    String[] name() default {};
						    Class<? extends Annotation>[] annotation() default {};
						}
						""", "package com.example; public @interface Primary {}",
						"package com.example; public class Client {}"));
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
				"annotation 만 있으면 반환 타입을 value 로 추가",
				"""
				import com.example.Client;
				import com.example.Primary;
				import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
				import org.springframework.context.annotation.Bean;

				class Config {
				    @Bean
				    @ConditionalOnMissingBean(annotation = Primary.class)
				    Client client() {
				        return new Client();
				    }
				}
				""",
				"""
				import com.example.Client;
				import com.example.Primary;
				import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
				import org.springframework.context.annotation.Bean;

				class Config {
				    @Bean
				    @ConditionalOnMissingBean(value = Client.class, annotation = Primary.class)
				    Client client() {
				        return new Client();
				    }
				}
				"""
			),
			Arguments.of(
				"제네릭 반환 타입은 raw 클래스",
				"""
				import java.util.List;
				import com.example.Primary;
				import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
				import org.springframework.context.annotation.Bean;

				class Config {
				    @Bean
				    @ConditionalOnBean(annotation = Primary.class)
				    List<String> names() {
				        return List.of();
				    }
				}
				""",
				"""
				import java.util.List;
				import com.example.Primary;
				import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
				import org.springframework.context.annotation.Bean;

				class Config {
				    @Bean
				    @ConditionalOnBean(value = List.class, annotation = Primary.class)
				    List<String> names() {
				        return List.of();
				    }
				}
				"""
			),
			Arguments.of(
				"이미 value 나 name 이 있으면 그대로",
				"""
				import com.example.Client;
				import com.example.Primary;
				import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
				import org.springframework.context.annotation.Bean;

				class Config {
				    @Bean
				    @ConditionalOnMissingBean(name = "client", annotation = Primary.class)
				    Client client() {
				        return new Client();
				    }

				    @Bean
				    @ConditionalOnMissingBean(Client.class)
				    Client other() {
				        return new Client();
				    }
				}
				""",
				null
			),
			Arguments.of(
				"@Bean 이 아닌 클래스 수준 조건은 그대로",
				"""
				import com.example.Primary;
				import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

				@ConditionalOnMissingBean(annotation = Primary.class)
				class Config {
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
