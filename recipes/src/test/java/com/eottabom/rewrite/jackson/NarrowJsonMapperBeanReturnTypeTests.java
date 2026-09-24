package com.eottabom.rewrite.jackson;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class NarrowJsonMapperBeanReturnTypeTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new NarrowJsonMapperBeanReturnType())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package org.springframework.context.annotation; public @interface Bean {}",
						"package tools.jackson.databind; public class ObjectMapper {}", """
								package tools.jackson.databind.json;
								public class JsonMapper extends tools.jackson.databind.ObjectMapper {
								    public static Builder builder() { return new Builder(); }
								    public static class Builder {
								        public Builder addModule(Object module) { return this; }
								        public JsonMapper build() { return new JsonMapper(); }
								    }
								}
								""", """
								package tools.jackson.dataformat.xml;
								public class XmlMapper extends tools.jackson.databind.ObjectMapper {}
								"""));
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
				"JsonMapper 를 돌려주는 @Bean ObjectMapper",
				"""
				import org.springframework.context.annotation.Bean;
				import tools.jackson.databind.ObjectMapper;
				import tools.jackson.databind.json.JsonMapper;

				class JacksonConfig {
				    @Bean
				    ObjectMapper objectMapper() {
				        return JsonMapper.builder()
				            .addModule(new Object())
				            .build();
				    }
				}
				""",
				"""
				import org.springframework.context.annotation.Bean;
				import tools.jackson.databind.json.JsonMapper;

				class JacksonConfig {
				    @Bean
				    JsonMapper objectMapper() {
				        return JsonMapper.builder()
				            .addModule(new Object())
				            .build();
				    }
				}
				"""
			),
			Arguments.of(
				"ObjectMapper 를 다른 곳에서도 쓰면 import 는 남긴다",
				"""
				import org.springframework.context.annotation.Bean;
				import tools.jackson.databind.ObjectMapper;
				import tools.jackson.databind.json.JsonMapper;

				class JacksonConfig {
				    @Bean
				    public ObjectMapper objectMapper() {
				        JsonMapper mapper = JsonMapper.builder().build();
				        return mapper;
				    }

				    String write(ObjectMapper mapper) {
				        return mapper.toString();
				    }
				}
				""",
				"""
				import org.springframework.context.annotation.Bean;
				import tools.jackson.databind.ObjectMapper;
				import tools.jackson.databind.json.JsonMapper;

				class JacksonConfig {
				    @Bean
				    public JsonMapper objectMapper() {
				        JsonMapper mapper = JsonMapper.builder().build();
				        return mapper;
				    }

				    String write(ObjectMapper mapper) {
				        return mapper.toString();
				    }
				}
				"""
			),
			Arguments.of(
				"XML 매퍼, new ObjectMapper(), @Bean 이 아닌 메서드는 그대로",
				"""
				import org.springframework.context.annotation.Bean;
				import tools.jackson.databind.ObjectMapper;
				import tools.jackson.databind.json.JsonMapper;
				import tools.jackson.dataformat.xml.XmlMapper;

				class JacksonConfig {
				    @Bean
				    ObjectMapper xmlMapper() {
				        return new XmlMapper();
				    }

				    @Bean
				    ObjectMapper plain() {
				        return new ObjectMapper();
				    }

				    ObjectMapper helper() {
				        return JsonMapper.builder().build();
				    }
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
