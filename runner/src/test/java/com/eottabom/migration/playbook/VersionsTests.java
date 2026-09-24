package com.eottabom.migration.playbook;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTests {

	@ParameterizedTest(name = "[{index}] {0} <=> {1} (기대값: {2})")
	@MethodSource("versionComparisonCases")
	void comparesVersions(String baseVersion, String targetVersion, int expectedSign) {
		assertThat(Integer.signum(Versions.compare(baseVersion, targetVersion))).isEqualTo(expectedSign);
	}

	// @formatter:off
	static Stream<Arguments> versionComparisonCases() {
		return Stream.of(
			Arguments.of("3.0.0-RC1", "3.0.0", -1),
			Arguments.of("7.0.0.Beta2", "7.0.0.CR1", -1),
			Arguments.of("7.0.0.CR1", "7.0.0.Final", -1),
			Arguments.of("3.2.0-M1", "3.2.0-M2", -1),
			Arguments.of("3.2.0-RC2", "3.2.0-SNAPSHOT", -1),
			Arguments.of("3.2.0-SNAPSHOT", "3.2.0", -1),
			Arguments.of("6.5.3.Final", "6.6.0.Alpha1", -1),
			Arguments.of("8.14.3", "8.4", 1),
			Arguments.of("6.6.2.Final", "6.6.2", 0),
			Arguments.of("2.0.6.RELEASE", "2.0.6", 0),
			Arguments.of("33.4.8-jre", "33.4.8", 0),
			Arguments.of("8.14", "8.14.0", 0)
		);
	}
	// @formatter:on

	@ParameterizedTest(name = "[{index}] 버전 \"{0}\" -> Major {1}")
	@CsvSource({ "9.1.0, 9", "8.14.3, 8", "17, 17" })
	void extractsMajorVersion(String version, int expectedMajor) {
		assertThat(Versions.major(version)).isEqualTo(expectedMajor);
	}

}
