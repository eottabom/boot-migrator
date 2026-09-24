package com.eottabom.rewrite.jackson;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class FixJacksonIOExceptionCatchTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new FixJacksonIOExceptionCatch())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package tools.jackson.core; public class JacksonException extends RuntimeException {}",
						"package tools.jackson.databind; public class ObjectMapper { public <T> T readValue(String s, Class<T> c) { return null; } }"));
	}

	@Test
	void addsIOExceptionWhenTryBodyThrowsIt() {
		rewriteRun(java("""
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load(Path path) {
				        try {
				            return objectMapper.readValue(Files.readString(path), Object.class);
				        } catch (JacksonException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				""", """
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object load(Path path) {
				        try {
				            return objectMapper.readValue(Files.readString(path), Object.class);
				        } catch (JacksonException | IOException e) {
				            throw new IllegalStateException(e);
				        }
				    }
				}
				"""));
	}

	@Test
	void leavesAloneWhenNoIOExceptionOrAlreadyCaught() {
		rewriteRun(java("""
				import java.io.IOException;
				import java.nio.file.Files;
				import java.nio.file.Path;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Loader {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) {
				        try {
				            return objectMapper.readValue(s, Object.class);
				        } catch (JacksonException e) {
				            return null;
				        }
				    }
				    Object load(Path path) {
				        try {
				            return objectMapper.readValue(Files.readString(path), Object.class);
				        } catch (JacksonException | IOException e) {
				            return null;
				        }
				    }
				}
				"""));
	}

	@Test
	void removesIOExceptionWhenNeverThrown() {
		rewriteRun(java("""
				import java.io.IOException;
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Parser {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) {
				        try {
				            return objectMapper.readValue(s, Object.class);
				        } catch (JacksonException | IOException e) {
				            return null;
				        }
				    }
				}
				""", """
				import tools.jackson.core.JacksonException;
				import tools.jackson.databind.ObjectMapper;

				class Parser {
				    ObjectMapper objectMapper = new ObjectMapper();
				    Object parse(String s) {
				        try {
				            return objectMapper.readValue(s, Object.class);
				        } catch (JacksonException e) {
				            return null;
				        }
				    }
				}
				"""));
	}

	@Test
	void ignoresStaleThrowsOfMigratedJacksonMethods() {
		// upstream 이 이름만 바꾼 상태: tools.jackson 메서드에 Jackson 2 의 throws IOException 정보가 남아
		// 있다
		rewriteRun((spec) -> spec.parser(JavaParser.fromJavaVersion()
			.dependsOn("package tools.jackson.core; public class JacksonException extends RuntimeException {}",
					"package tools.jackson.databind; public class ObjectMapper { public String writeValueAsString(Object o) throws java.io.IOException { return null; } }")),
				java("""
						import java.io.IOException;
						import tools.jackson.core.JacksonException;
						import tools.jackson.databind.ObjectMapper;

						class Writer {
						    ObjectMapper objectMapper = new ObjectMapper();
						    String write(Object o) {
						        try {
						            return objectMapper.writeValueAsString(o);
						        } catch (JacksonException | IOException e) {
						            return null;
						        }
						    }
						}
						""", """
						import tools.jackson.core.JacksonException;
						import tools.jackson.databind.ObjectMapper;

						class Writer {
						    ObjectMapper objectMapper = new ObjectMapper();
						    String write(Object o) {
						        try {
						            return objectMapper.writeValueAsString(o);
						        } catch (JacksonException e) {
						            return null;
						        }
						    }
						}
						"""));
	}

}
