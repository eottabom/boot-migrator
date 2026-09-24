package com.eottabom.rewrite.logging;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class FixLogstashDecoratorForJackson3Tests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipeFromResources("com.eottabom.rewrite.logging.FixLogstashDecoratorForJackson3")
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package tools.jackson.core; public interface ObjectWriteContext {}",
						"package tools.jackson.core; public abstract class JsonGenerator { public abstract ObjectWriteContext objectWriteContext(); }",
						"package tools.jackson.databind; public enum DeserializationFeature { USE_BIG_DECIMAL_FOR_FLOATS }",
						"package tools.jackson.databind; public class ObjectMapper { public ObjectMapper enable(DeserializationFeature f) { return this; } }",
						"package net.logstash.logback.decorate; public interface JsonGeneratorDecorator { tools.jackson.core.JsonGenerator decorate(tools.jackson.core.JsonGenerator g); }"));
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
				"removes mutation and cast in decorator",
				"""
				import tools.jackson.core.JsonGenerator;
				import tools.jackson.databind.DeserializationFeature;
				import tools.jackson.databind.ObjectMapper;
				import net.logstash.logback.decorate.JsonGeneratorDecorator;

				public class BigDecimalJsonGeneratorDecorator implements JsonGeneratorDecorator {
				    @Override
				    public JsonGenerator decorate(JsonGenerator generator) {
				        ObjectMapper objectMapper = (ObjectMapper) generator.objectWriteContext();
				        objectMapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
				        return generator;
				    }
				}
				""",
				"""
				import tools.jackson.core.JsonGenerator;
				import net.logstash.logback.decorate.JsonGeneratorDecorator;

				public class BigDecimalJsonGeneratorDecorator implements JsonGeneratorDecorator {
				    @Override
				    public JsonGenerator decorate(JsonGenerator generator) {
				        return generator;
				    }
				}
				"""
			),
			Arguments.of(
				"leaves other classes alone",
				"""
				import tools.jackson.databind.DeserializationFeature;
				import tools.jackson.databind.ObjectMapper;

				class Config {
				    ObjectMapper mapper() {
				        ObjectMapper m = new ObjectMapper();
				        m.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
				        return m;
				    }
				}
				""",
				null
			)
		);
	}
	// @formatter:on

}
