package com.eottabom.migration.playbook;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTests {

	@ParameterizedTest
	@CsvSource({ "3.0.0-RC1, 3.0.0, -1", "7.0.0.Beta2, 7.0.0.CR1, -1", "7.0.0.CR1, 7.0.0.Final, -1",
			"3.2.0-M1, 3.2.0-M2, -1", "3.2.0-RC2, 3.2.0-SNAPSHOT, -1", "3.2.0-SNAPSHOT, 3.2.0, -1",
			"6.5.3.Final, 6.6.0.Alpha1, -1", "8.14.3, 8.4, 1", "6.6.2.Final, 6.6.2, 0", "2.0.6.RELEASE, 2.0.6, 0",
			"33.4.8-jre, 33.4.8, 0", "8.14, 8.14.0, 0" })
	void comparesVersions(String a, String b, int expectedSign) {
		assertThat(Integer.signum(Versions.compare(a, b))).isEqualTo(expectedSign);
	}

	@ParameterizedTest
	@CsvSource({ "9.1.0, 9", "8.14.3, 8", "17, 17" })
	void extractsMajorVersion(String version, int major) {
		assertThat(Versions.major(version)).isEqualTo(major);
	}

}
