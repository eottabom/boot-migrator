package com.eottabom.migration.playbook;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTests {

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("versionComparisonCases")
	void comparesVersions(String scenario, String a, String b, int expectedSign) {
		assertThat(Integer.signum(Versions.compare(a, b))).isEqualTo(expectedSign);
	}

	static Stream<Arguments> versionComparisonCases() {
		return Stream.of(Arguments.of("RC1은 정식 릴리스보다 낮다", "3.0.0-RC1", "3.0.0", -1),
				Arguments.of("Beta는 CR보다 낮다", "7.0.0.Beta2", "7.0.0.CR1", -1),
				Arguments.of("CR은 Final보다 낮다", "7.0.0.CR1", "7.0.0.Final", -1),
				Arguments.of("마일스톤 M1은 M2보다 낮다", "3.2.0-M1", "3.2.0-M2", -1),
				Arguments.of("RC는 SNAPSHOT보다 낮다", "3.2.0-RC2", "3.2.0-SNAPSHOT", -1),
				Arguments.of("SNAPSHOT은 정식 릴리스보다 낮다", "3.2.0-SNAPSHOT", "3.2.0", -1),
				Arguments.of("버전 숫자가 한정자보다 우선한다 (6.5.3.Final < 6.6.0.Alpha1)", "6.5.3.Final", "6.6.0.Alpha1", -1),
				Arguments.of("숫자 비교 시 사전순이 아닌 수치로 비교한다 (8.14.3 > 8.4)", "8.14.3", "8.4", 1),
				Arguments.of("Final 한정자는 접미사 없는 버전과 동등하게 처리한다", "6.6.2.Final", "6.6.2", 0),
				Arguments.of("RELEASE 한정자는 접미사 없는 버전과 동등하게 처리한다", "2.0.6.RELEASE", "2.0.6", 0),
				Arguments.of("jre 한정자는 접미사 없는 버전과 동등하게 처리한다", "33.4.8-jre", "33.4.8", 0),
				Arguments.of("마지막 자리 0 생략 표기는 동일 버전으로 본다 (8.14 == 8.14.0)", "8.14", "8.14.0", 0));
	}

	@ParameterizedTest(name = "[{index}] 버전 \"{0}\" -> Major {1}")
	@CsvSource({ "9.1.0, 9", "8.14.3, 8", "17, 17" })
	void extractsMajorVersion(String version, int major) {
		assertThat(Versions.major(version)).isEqualTo(major);
	}

}
