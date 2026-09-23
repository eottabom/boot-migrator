package com.eottabom.migration.task;

import com.eottabom.migration.model.MigrationRequest;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** 실행할 단계와 레시피를 보여준다. 대상 프로젝트의 Gradle 을 띄우지 않는다. */
public abstract class MigrationPlanTask extends MigrationTask {

    public MigrationPlanTask() {
        setDescription("대상 프로젝트에서 실행할 단계와 레시피를 보여준다 (--project-path, --spring-boot, --java)");
    }

    @Internal
    @Option(option = "spring-boot", description = "목표 Boot 단계: 3.0 ~ 3.5 | 4.0 | 4.1 (기본: 최종 단계)")
    public abstract Property<String> getSpringBoot();

    @Internal
    @Option(option = "java", description = "Java 버전업: auto(기본: 목표 Boot 가 지원하면 유지) | latest(목표 Boot 가 지원하는 최신 LTS) | 17 | 21 | 25 | none")
    public abstract Property<String> getJava();

    @Internal
    @Option(option = "one-shot", description = "단계별 게이트 없이 목표 레시피를 한 번에 적용한다")
    public abstract Property<Boolean> getOneShot();

    @Internal
    @Option(option = "upstream-only", description = "커스텀 레시피 없이 upstream UpgradeSpringBoot_X_Y 만 (비교용)")
    public abstract Property<Boolean> getUpstreamOnly();

    @Internal
    @Option(option = "skip-project-recipes", description = "대상 프로젝트의 .rewrite/ 레시피를 붙이지 않는다")
    public abstract Property<Boolean> getSkipProjectRecipes();

    @TaskAction
    public void execute() {
        perform();
    }

    protected void perform() {
        runner().plan(request());
    }

    protected MigrationRequest request() {
        return new MigrationRequest(projectDir(), getSpringBoot().getOrNull(), getJava().getOrElse("auto"), "build",
                false, false, getOneShot().getOrElse(false), getUpstreamOnly().getOrElse(false), false, keepJavaHome(),
                getSkipProjectRecipes().getOrElse(false));
    }
}
