package com.eottabom.migration.task;

import com.eottabom.migration.model.MigrationRequest;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;

/**
 * 현재 버전의 다음 단계부터 목표까지 단계마다 rewriteRun → compile → build → 리포트 → (commit). 리포트와 패치는 대상
 * 프로젝트의 .rewrite-migration/ 에 남는다.
 */
public abstract class MigrationRunTask extends MigrationPlanTask {

	public MigrationRunTask() {
		setDescription("대상 프로젝트를 목표 Spring Boot 까지 단계별로 마이그레이션한다 (--project-path, --spring-boot, ...)");
	}

	@Internal
	@Option(option = "gate", description = "compile | build(기본: 컴파일 + 전체 테스트 + 패키징/asciidoctor/checkstyle 등) | none")
	public abstract Property<String> getGate();

	@Internal
	@Option(option = "commit", description = "게이트를 통과한 단계마다 git commit (작업 트리가 깨끗해야 한다)")
	public abstract Property<Boolean> getCommit();

	/** Gradle 자체의 --dry-run 과 겹치지 않도록 preview 라는 이름을 쓴다 */
	@Internal
	@Option(option = "preview", description = "소스를 바꾸지 않고 단계별 patch 만 만든다. git 저장소면 임시 worktree 에서 모든 단계를 미리 본다")
	public abstract Property<Boolean> getPreview();

	@Internal
	@Option(option = "allow-dirty", description = "커밋되지 않은 변경이 있어도 시작한다")
	public abstract Property<Boolean> getAllowDirty();

	@Override
	protected void perform() {
		runner().run(request());
	}

	@Override
	protected MigrationRequest request() {
		MigrationRequest base = super.request();
		return new MigrationRequest(base.projectDir(), base.targetBoot(), base.targetJava(),
				normalizeGate(getGate().getOrElse("build")), getCommit().getOrElse(false),
				getPreview().getOrElse(false), base.oneShot(), base.upstreamOnly(), getAllowDirty().getOrElse(false),
				base.keepJavaHome(), base.skipProjectRecipes());
	}

	/** test 는 build 의 옛 이름. */
	static String normalizeGate(String gate) {
		return gate.equals("test") ? "build" : gate;
	}

}
