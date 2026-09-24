package com.eottabom.rewrite.kafka;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class UseJsonMapperForJacksonJsonSerializerTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipeFromResources("com.eottabom.rewrite.kafka.UseJsonMapperForJacksonJsonSerializer")
			.typeValidationOptions(TypeValidation.none())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package tools.jackson.databind; public class ObjectMapper {}",
						"package tools.jackson.databind.json; public class JsonMapper extends tools.jackson.databind.ObjectMapper {}",
						"package org.springframework.kafka.support.serializer; public class JacksonJsonSerializer<T> { public JacksonJsonSerializer() {} public JacksonJsonSerializer(tools.jackson.databind.json.JsonMapper m) {} }"));
	}

	@Test
	void changesObjectMapperWhereSerializerIsUsed() {
		rewriteRun(java("""
				import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
				import tools.jackson.databind.ObjectMapper;

				class KafkaConfig {
				    static class CustomJsonSerializer extends JacksonJsonSerializer<Object> {
				        CustomJsonSerializer(ObjectMapper objectMapper) {
				            super(objectMapper);
				        }
				    }
				}
				""", """
				import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
				import tools.jackson.databind.json.JsonMapper;

				class KafkaConfig {
				    static class CustomJsonSerializer extends JacksonJsonSerializer<Object> {
				        CustomJsonSerializer(JsonMapper objectMapper) {
				            super(objectMapper);
				        }
				    }
				}
				"""), java("""
				import tools.jackson.databind.ObjectMapper;

				class Other {
				    ObjectMapper mapper;
				}
				"""));
	}

}
