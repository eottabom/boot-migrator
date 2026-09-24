package com.eottabom.migration.playbook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionsTest {

    @Test
    void comparesNumbersThenQualifiers() {
        assertThat(Versions.compare("3.0.0-RC1", "3.0.0")).isNegative();
        assertThat(Versions.compare("7.0.0.Beta2", "7.0.0.CR1")).isNegative();
        assertThat(Versions.compare("7.0.0.CR1", "7.0.0.Final")).isNegative();
        assertThat(Versions.compare("3.2.0-M1", "3.2.0-M2")).isNegative();
        assertThat(Versions.compare("3.2.0-RC2", "3.2.0-SNAPSHOT")).isNegative();
        assertThat(Versions.compare("3.2.0-SNAPSHOT", "3.2.0")).isNegative();
    }

    @Test
    void treatsReleaseQualifiersAsEqual() {
        assertThat(Versions.compare("6.6.2.Final", "6.6.2")).isZero();
        assertThat(Versions.compare("2.0.6.RELEASE", "2.0.6")).isZero();
        assertThat(Versions.compare("33.4.8-jre", "33.4.8")).isZero();
        assertThat(Versions.compare("8.14", "8.14.0")).isZero();
    }

    @Test
    void numbersTakePrecedence() {
        assertThat(Versions.compare("6.5.3.Final", "6.6.0.Alpha1")).isNegative();
        assertThat(Versions.compare("8.14.3", "8.4")).isPositive();
        assertThat(Versions.major("9.1.0")).isEqualTo(9);
    }
}
