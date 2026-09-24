package com.eottabom.rewrite.logging;

import org.junit.jupiter.api.Test;
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

	@Test
	void removesMutationAndCastInDecorator() {
		rewriteRun(java("""
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
				""", """
				import tools.jackson.core.JsonGenerator;
				import net.logstash.logback.decorate.JsonGeneratorDecorator;

				public class BigDecimalJsonGeneratorDecorator implements JsonGeneratorDecorator {
				    @Override
				    public JsonGenerator decorate(JsonGenerator generator) {
				        return generator;
				    }
				}
				"""));
	}

	@Test
	void leavesOtherClassesAlone() {
		rewriteRun(java("""
				import tools.jackson.databind.DeserializationFeature;
				import tools.jackson.databind.ObjectMapper;

				class Config {
				    ObjectMapper mapper() {
				        ObjectMapper m = new ObjectMapper();
				        m.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
				        return m;
				    }
				}
				"""));
	}

}
