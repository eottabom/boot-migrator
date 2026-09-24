package com.eottabom.migration.model;

import java.util.List;

import com.eottabom.migration.playbook.Compatibility.BootLine;

/**
 * @param targetJava 올리지 않으면 null
 * @param stages 현재 버전의 다음 단계부터 목표까지. 비어 있으면 할 일이 없다
 * @param notes Java / Gradle 판단 근거와 경고
 */
public record MigrationPlan(String targetBoot, BootLine targetLine, Integer targetJava, List<Stage> stages,
		List<String> notes) {

	public boolean isEmpty() {
		return this.stages.isEmpty();
	}

	public String stageNames() {
		return String.join(" ", this.stages.stream().map(Stage::name).toList());
	}
}
