package com.eottabom.migration.model;

import java.nio.file.Path;

/**
 * 대상 프로젝트의 현재 상태.
 *
 * @param bootVersion   루트 build.gradle(.kts) / gradle.properties 에서 찾은 Spring Boot 버전. 없으면 null
 * @param gradleVersion gradle-wrapper.properties 의 배포 버전. 없으면 null
 * @param javaVersion   빌드 파일에 선언된 Java 버전 중 가장 낮은 값 (마이그레이션 기준). 없으면 null
 * @param toolchainJava 빌드 파일에 선언된 toolchain / VERSION_NN 중 가장 높은 값 (Gradle 을 띄울 JDK 선택용). 없으면 null
 * @param git           git 저장소 여부
 * @param dirty         커밋되지 않은 변경 존재 여부 (git 이 아니면 false)
 */
public record ProjectModel(
        Path dir,
        String bootVersion,
        String gradleVersion,
        Integer javaVersion,
        Integer toolchainJava,
        boolean git,
        boolean dirty
) {
}
