package com.eottabom.migration.inspect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import com.eottabom.migration.model.ProjectModel;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInspectorTests {

	@TempDir
	Path dir;

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("buildLayouts")
	void readsVersionsFromBuildFiles(String scenario, Map<String, String> files, String boot, String gradle,
			Integer java, Integer toolchainJava) throws IOException {
		for (Map.Entry<String, String> file : files.entrySet()) {
			write(file.getKey(), file.getValue());
		}

		ProjectModel model = new ProjectInspector().inspect(this.dir);

		assertThat(model.bootVersion()).isEqualTo(boot);
		assertThat(model.gradleVersion()).isEqualTo(gradle);
		assertThat(model.javaVersion()).isEqualTo(java);
		assertThat(model.toolchainJava()).isEqualTo(toolchainJava);
		assertThat(model.git()).isFalse();
	}

	// @formatter:off
	static Stream<Arguments> buildLayouts() {
		return Stream.of(
			Arguments.of(
				"Groovy DSL 플러그인 버전, 모듈별 Java, build 아래 파일은 무시",
				Map.of(
					"build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.2.8' apply false\n}\n",
					"api/build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n",
					"batch/build.gradle", "sourceCompatibility = '17'\n",
					"build/generated/build.gradle", "sourceCompatibility = '11'\n",
					"gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n"),
				"3.2.8", "8.8", 17, 21
			),
			Arguments.of(
				"Kotlin DSL 플러그인 버전",
				Map.of("build.gradle.kts", "plugins {\n    id(\"org.springframework.boot\") version \"3.4.1\"\n}\n"),
				"3.4.1", null, null, null
			),
			Arguments.of(
				"gradle.properties 의 springBootVersion",
				Map.of("gradle.properties", "springBootVersion='2.7.18'\n"),
				"2.7.18", null, null, null
			),
			Arguments.of(
				"version catalog 의 Boot 플러그인과 Java",
				Map.of(
					"build.gradle", "plugins {\n    alias(libs.plugins.spring.boot) apply false\n}\n",
					"api/build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInteger()) } }\n",
					"gradle/libs.versions.toml", """
						[versions]
						java = "25"
						spring-boot = "4.0.7"  # boot

						[plugins]
						spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
						"""),
				"4.0.7", null, 25, 25
			),
			Arguments.of(
				"루트에 없으면 서브프로젝트 선언 중 가장 낮은 버전",
				Map.of(
					"build.gradle", "plugins { id 'base' }\n",
					"a/build.gradle", "plugins { id 'org.springframework.boot' version '3.5.3' }\n",
					"b/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.4.1\" }\n"),
				"3.4.1", null, null, null
			),
			Arguments.of(
				"Java 8 표기 (VERSION_1_8)",
				Map.of("build.gradle", "java { sourceCompatibility = JavaVersion.VERSION_1_8 }\n"),
				null, null, 8, 8
			)
		);
	}
	// @formatter:on

	private void write(String path, String content) throws IOException {
		Path file = this.dir.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

}
