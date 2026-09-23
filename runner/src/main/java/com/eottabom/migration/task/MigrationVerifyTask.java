package com.eottabom.migration.task;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** 현재 소스의 컴파일(+제거 예정 API 경고)과 build(전체 테스트 + 패키징). 소스는 바꾸지 않는다. */
public abstract class MigrationVerifyTask extends MigrationTask {

    public MigrationVerifyTask() {
        setDescription("대상 프로젝트의 컴파일과 테스트를 검증한다 (--project-path, --gate)");
    }

    @Internal
    @Option(option = "gate", description = "compile | build(기본: 컴파일 + 전체 테스트 + 패키징)")
    public abstract Property<String> getGate();

    @TaskAction
    public void verify() {
        runner().verify(projectDir(), MigrationRunTask.normalizeGate(getGate().getOrElse("build")), keepJavaHome());
    }
}
