package com.eottabom.rewrite.gradle;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.gradle.Assertions.buildGradle;
import static org.openrewrite.gradle.Assertions.settingsGradle;
import static org.openrewrite.gradle.Assertions.settingsGradleKts;

class RemoveDependenciesFromSettingsScriptTests implements RewriteTest {

	@Override
	public void defaults(RecipeSpec spec) {
		spec.recipe(new RemoveDependenciesFromSettingsScript());
	}

	@Test
	void kotlinDsl() {
		rewriteRun(settingsGradleKts("""
				rootProject.name = "spring-benchmark"

				dependencies {
				    runtimeOnly("org.springframework.boot:spring-boot-mongodb:4.1.1")
				}
				""", """
				rootProject.name = "spring-benchmark"
				"""));
	}

	@Test
	void groovyDsl() {
		rewriteRun(settingsGradle("""
				rootProject.name = 'app'
				dependencies {
				    runtimeOnly 'org.springframework.boot:spring-boot-mongodb:4.1.1'
				}
				include 'api'
				""", """
				rootProject.name = 'app'
				include 'api'
				"""));
	}

	@Test
	void keepsNestedDependenciesAndBuildFiles() {
		rewriteRun(settingsGradle("""
				buildscript {
				    dependencies {
				        classpath 'com.example:plugin:1.0'
				    }
				}
				rootProject.name = 'app'
				"""), buildGradle("""
				plugins { id 'java' }
				dependencies {
				    implementation 'org.apache.commons:commons-lang3:3.17.0'
				}
				"""));
	}

}
