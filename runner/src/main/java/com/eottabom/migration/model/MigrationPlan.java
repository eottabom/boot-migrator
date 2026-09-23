package com.eottabom.migration.model;

import com.eottabom.migration.playbook.Compatibility.BootLine;

import java.util.List;

/**
 * @param targetBoot 목표 Spring Boot 단계 (예: "4.1")
 * @param targetLine 목표 Boot 의 호환성 (Java / Gradle 범위, Spring Cloud 트레인)
 * @param targetJava 목표 Java 버전. 올리지 않으면 null
 * @param stages     현재 버전의 다음 단계부터 목표까지 순서대로. 비어 있으면 할 일이 없다
 * @param notes      Java / Gradle 판단 근거와 경고
 */
public record MigrationPlan(String targetBoot, BootLine targetLine, Integer targetJava, List<Stage> stages, List<String> notes) {

    public boolean isEmpty() {
        return stages.isEmpty();
    }

    public String stageNames() {
        return String.join(" ", stages.stream().map(Stage::name).toList());
    }
}
