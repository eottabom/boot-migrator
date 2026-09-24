package com.eottabom.migration.inspect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.eottabom.migration.model.ProjectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectInspectorTests {

	@TempDir
	Path dir;

	@Test
	void readsGroovyPluginVersionAndModuleJavaVersions() throws IOException {
		write("build.gradle", "plugins {\n    id 'org.springframework.boot' version '3.2.8' apply false\n}\n");
		write("api/build.gradle", "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n");
		write("batch/build.gradle", "sourceCompatibility = '17'\n");
		write("build/generated/build.gradle", "sourceCompatibility = '11'\n");
		write("gradle/wrapper/gradle-wrapper.properties",
				"distributionUrl=https\\://services.gradle.org/distributions/gradle-8.8-bin.zip\n");

		ProjectModel model = new ProjectInspector().inspect(this.dir);

		assertThat(model.bootVersion()).isEqualTo("3.2.8");
		assertThat(model.gradleVersion()).isEqualTo("8.8");
		assertThat(model.javaVersion()).isEqualTo(17);
		assertThat(model.toolchainJava()).isEqualTo(21);
		assertThat(model.git()).isFalse();
	}

	@Test
	void readsKotlinDslAndGradleProperties() throws IOException {
		write("build.gradle.kts", "plugins {\n    id(\"org.springframework.boot\") version \"3.4.1\"\n}\n");
		assertThat(new ProjectInspector().bootVersion(this.dir)).isEqualTo("3.4.1");

		Files.delete(this.dir.resolve("build.gradle.kts"));
		write("gradle.properties", "springBootVersion='2.7.18'\n");
		assertThat(new ProjectInspector().bootVersion(this.dir)).isEqualTo("2.7.18");
	}

	@Test
	void readsBootPluginAndJavaFromVersionCatalog() throws IOException {
		write("build.gradle", "plugins {\n    alias(libs.plugins.spring.boot) apply false\n}\n");
		write("api/build.gradle",
				"java { toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInteger()) } }\n");
		write("gradle/libs.versions.toml", """
				[versions]
				java = "25"
				spring-boot = "4.0.7"  # boot

				[plugins]
				spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
				""");

		ProjectModel model = new ProjectInspector().inspect(this.dir);

		assertThat(model.bootVersion()).isEqualTo("4.0.7");
		assertThat(model.javaVersion()).isEqualTo(25);
		assertThat(model.toolchainJava()).isEqualTo(25);
	}

	@Test
	void usesLowestSubprojectBootVersionWhenRootHasNone() throws IOException {
		write("build.gradle", "plugins { id 'base' }\n");
		write("a/build.gradle", "plugins { id 'org.springframework.boot' version '3.5.3' }\n");
		write("b/build.gradle.kts", "plugins { id(\"org.springframework.boot\") version \"3.4.1\" }\n");

		assertThat(new ProjectInspector().bootVersion(this.dir)).isEqualTo("3.4.1");
	}

	@Test
	void readsJava8Notation() throws IOException {
		write("build.gradle", "java { sourceCompatibility = JavaVersion.VERSION_1_8 }\n");

		assertThat(new ProjectInspector().inspect(this.dir).javaVersion()).isEqualTo(8);
	}

	private void write(String path, String content) throws IOException {
		Path file = this.dir.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

}
