package com.eottabom.migration.task;

import org.gradle.api.tasks.TaskAction;

/** 현재 Boot / Gradle / Java 버전, resolve 된 의존성, 수동 검토 대상 위치. 소스는 바꾸지 않는다. */
public abstract class MigrationAnalyzeTask extends MigrationTask {

	public MigrationAnalyzeTask() {
		setDescription("대상 프로젝트의 현재 상태와 수동 검토 대상을 분석한다 (--project-path)");
	}

	@TaskAction
	public void analyze() {
		runner().analyze(projectDir(), keepJavaHome());
	}

}
