package com.eottabom.rewrite.gradle;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openrewrite.semver.LatestRelease;

import static org.assertj.core.api.Assertions.assertThat;

class VersionCatalogEditorTests {

	/** 3.4.x 는 3.4.9, 3.x 는 3.9.0 을 최신으로 본다 */
	private static final VersionCatalogEditor.Resolver RESOLVER = (group, artifact, current, newVersion, pattern,
			plugin) -> {
		String latest = newVersion.endsWith(".x") ? newVersion.replaceAll("\\.x$",
				(newVersion.chars().filter((c) -> c == '.').count() > 1) ? ".9" : ".9.0") : newVersion;
		return (current == null || new LatestRelease(null).compare(null, current, latest) < 0) ? latest : null;
	};

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("scenarios")
	void appliesRules(String scenario, String rules, String before, String after) {
		assertThat(VersionCatalogEditor.apply(before,
				Arrays.stream(rules.split(";")).map(VersionCatalogEditor.Rule::parse).toList(), RESOLVER))
			.isEqualTo(after);
	}

	// @formatter:off
	static Stream<Arguments> scenarios() {
		return Stream.of(
			Arguments.of(
				"Boot 플러그인 version.ref",
				"plugin org.springframework.boot 3.4.x",
				"""
				[versions]
				spring-boot = "3.3.5" # boot
				[plugins]
				spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
				""",
				"""
				[versions]
				spring-boot = "3.4.9" # boot
				[plugins]
				spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
				"""
			),
			Arguments.of(
				"플러그인 문자열 표기와 glob",
				"plugin io.freefair.* 8.x",
				"""
				[plugins]
				lombok = "io.freefair.lombok:6.6.3"
				""",
				"""
				[plugins]
				lombok = "io.freefair.lombok:8.9.0"
				"""
			),
			Arguments.of(
				"BOM 라이브러리 inline version",
				"dependency org.springframework.cloud:spring-cloud-dependencies 2024.0.x",
				"""
				[libraries]
				cloud-bom = { module = "org.springframework.cloud:spring-cloud-dependencies", version = "2023.0.3" }
				""",
				"""
				[libraries]
				cloud-bom = { module = "org.springframework.cloud:spring-cloud-dependencies", version = "2024.0.9" }
				"""
			),
			Arguments.of(
				"group / name 표기와 문자열 표기",
				"dependency org.mockito:* 5.x",
				"""
				[libraries]
				mockito-core = { group = "org.mockito", name = "mockito-core", version = "4.11.0" }
				mockito-junit = "org.mockito:mockito-junit-jupiter:4.11.0"
				""",
				"""
				[libraries]
				mockito-core = { group = "org.mockito", name = "mockito-core", version = "5.9.0" }
				mockito-junit = "org.mockito:mockito-junit-jupiter:5.9.0"
				"""
			),
			Arguments.of(
				"이미 높으면 내리지 않고, BOM 관리 항목과 rich version 은 건드리지 않음",
				"dependency org.springframework.boot:* 3.4.x;dependency com.example:* 1.x",
				"""
				[versions]
				boot = "3.5.0"
				[libraries]
				boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "boot" }
				web = { module = "org.springframework.boot:spring-boot-starter-web" }
				pinned = { module = "com.example:lib", version = { strictly = "0.9" } }
				""",
				"""
				[versions]
				boot = "3.5.0"
				[libraries]
				boot-bom = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "boot" }
				web = { module = "org.springframework.boot:spring-boot-starter-web" }
				pinned = { module = "com.example:lib", version = { strictly = "0.9" } }
				"""
			),
			Arguments.of(
				"좌표 변경은 버전 키를 같이 쓰는 다른 항목을 남기고 새 키를 만든다",
				"change com.fasterxml.jackson.core:jackson-databind tools.jackson.core:* 3.1.x",
				"""
				[versions]
				jackson = "2.15.0"
				[libraries]
				jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
				jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version.ref = "jackson" }
				""",
				"""
				[versions]
				jackson = "2.15.0"
				jackson-databind = "3.1.9"
				[libraries]
				jackson-databind = { module = "tools.jackson.core:jackson-databind", version.ref = "jackson-databind" }
				jackson-annotations = { module = "com.fasterxml.jackson.core:jackson-annotations", version.ref = "jackson" }
				"""
			),
			Arguments.of(
				"좌표 변경 대상만 쓰는 버전 키는 그대로 올린다",
				"change com.github.tomakehurst:wiremock-jre8 org.wiremock:wiremock 3.x",
				"""
				[versions]
				wiremock = "2.35.0"
				[libraries]
				wiremock = { module = "com.github.tomakehurst:wiremock-jre8", version.ref = "wiremock" }
				""",
				"""
				[versions]
				wiremock = "3.9.0"
				[libraries]
				wiremock = { module = "org.wiremock:wiremock", version.ref = "wiremock" }
				"""
			),
			Arguments.of(
				"버전 없는 항목은 좌표만 바꾼다",
				"change javax.servlet:javax.servlet-api jakarta.servlet:jakarta.servlet-api 6.x",
				"""
				[libraries]
				servlet = { module = "javax.servlet:javax.servlet-api" }
				""",
				"""
				[libraries]
				servlet = { module = "jakarta.servlet:jakarta.servlet-api" }
				"""
			)
		);
	}
	// @formatter:on

}
