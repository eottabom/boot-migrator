package com.eottabom.rewrite.lombok;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.SourceSpecs;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class CopyJacksonAnnotationsToAccessorsTests implements RewriteTest {

	private static final String DTO = """
			import com.fasterxml.jackson.annotation.JsonProperty;
			import lombok.Data;

			@Data
			class Response {
			    @JsonProperty("isShow")
			    private boolean isShow;
			}
			""";

	private static final String CONFIGURED = "lombok.copyJacksonAnnotationsToAccessors = true\n";

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new CopyJacksonAnnotationsToAccessors())
			.parser(JavaParser.fromJavaVersion()
				.dependsOn("package lombok; public @interface Data {}",
						"package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }"));
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("lombokConfigs")
	void writesLombokConfig(String scenario, String before, String after) {
		rewriteRun(java(DTO), file("lombok.config", before, after));
	}

	// @formatter:off
	static Stream<Arguments> lombokConfigs() {
		return Stream.of(
			Arguments.of("lombok.config 가 없으면 만든다", null, CONFIGURED),
			Arguments.of("있으면 한 줄 추가",
				"lombok.addLombokGeneratedAnnotation = true",
				"lombok.addLombokGeneratedAnnotation = true\n" + CONFIGURED),
			Arguments.of("키가 이미 있으면 값이 달라도 그대로",
				"lombok.copyJacksonAnnotationsToAccessors = false\n",
				"lombok.copyJacksonAnnotationsToAccessors = false\n")
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("gitignores")
	void keepsLombokConfigCommitted(String scenario, String before, String after) {
		rewriteRun(java(DTO), file("lombok.config", CONFIGURED, CONFIGURED), file(".gitignore", before, after));
	}

	// @formatter:off
	static Stream<Arguments> gitignores() {
		return Stream.of(
			Arguments.of("lombok.config 를 무시하면 루트 파일만 다시 포함",
				"""
				.idea/
				lombok.config
				""",
				"""
				.idea/
				lombok.config
				# 루트 lombok.config 는 커밋한다 (lombok.copyJacksonAnnotationsToAccessors)
				!/lombok.config
				"""),
			Arguments.of("무시하지 않으면 그대로", ".idea/\n", ".idea/\n")
		);
	}
	// @formatter:on

	@Test
	void leavesProjectWithoutJacksonAnnotations() {
		rewriteRun(java("""
				import lombok.Data;

				@Data
				class Plain {
				    private boolean isShow;
				}
				"""));
	}

	/** before 와 after 가 같으면 바뀌지 않아야 하는 파일 */
	private static SourceSpecs file(String path, String before, String after) {
		return (before != null && before.equals(after)) ? text(before, (spec) -> spec.path(path))
				: text(before, after, (spec) -> spec.path(path));
	}

}
