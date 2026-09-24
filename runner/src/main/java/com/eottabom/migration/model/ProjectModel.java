package com.eottabom.migration.model;

import java.nio.file.Path;

/**
 * 대상 프로젝트의 현재 상태. 찾지 못한 버전은 null.
 *
 * @param javaVersion 빌드 파일에 선언된 Java 버전 중 가장 낮은 값 (마이그레이션 기준)
 * @param toolchainJava 선언된 toolchain / VERSION_NN 중 가장 높은 값 (대상 Gradle 을 띄울 JDK 선택용)
 * @param dirty 커밋되지 않은 변경이 있다 (git 이 아니면 false)
 */
public record ProjectModel(Path dir, String bootVersion, String gradleVersion, Integer javaVersion,
		Integer toolchainJava, boolean git, boolean dirty) {
}
