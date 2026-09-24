package com.eottabom.migration.task;

import java.nio.file.Files;
import java.nio.file.Path;

import com.eottabom.migration.exec.MigrationRunner;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;

/**
 * 대상 프로젝트를 다루는 태스크의 공통 옵션. 대상 프로젝트는 이 빌드의 입력/출력이 아니므로 up-to-date 검사를 하지 않는다.
 */
public abstract class MigrationTask extends DefaultTask {

	protected MigrationTask() {
		setGroup("migration");
		doNotTrackState("대상 프로젝트는 이 빌드 밖에 있다");
	}

	@Internal
	@Option(option = "project-path", description = "버전업 대상 프로젝트 경로 (필수)")
	public abstract Property<String> getProjectPath();

	@Internal
	@Option(option = "keep-java-home", description = "JDK 자동 선택을 끄고 현재 JAVA_HOME 으로 대상 프로젝트를 실행한다")
	public abstract Property<Boolean> getKeepJavaHome();

	/** 명령을 실행한 위치. 상대 경로 --project-path 의 기준 */
	@Internal
	public abstract DirectoryProperty getInvocationDir();

	@Internal
	@Option(option = "gradle-jvmargs", description = "대상 프로젝트 Gradle 데몬 JVM 옵션 (기본: -Xmx 는 장비 메모리의 절반, 최대 6g)")
	public abstract Property<String> getGradleJvmArgs();

	@Internal
	public abstract RegularFileProperty getRewriteInitScript();

	@Internal
	public abstract RegularFileProperty getVerifyInitScript();

	@Internal
	public abstract DirectoryProperty getRecipeLibs();

	@Internal
	public abstract DirectoryProperty getPlaybookDir();

	protected Path projectDir() {
		if (!getProjectPath().isPresent()) {
			throw new GradleException("--project-path=<대상 프로젝트 경로> 가 필요하다. 옵션 안내: ./gradlew migrationHelp");
		}
		Path dir = getInvocationDir().get()
			.getAsFile()
			.toPath()
			.resolve(getProjectPath().get())
			.normalize()
			.toAbsolutePath();
		if (!Files.isRegularFile(dir.resolve("gradlew"))) {
			throw new GradleException("Gradle wrapper(gradlew) 가 있는 프로젝트가 아니다: " + dir);
		}
		return dir;
	}

	protected boolean keepJavaHome() {
		return getKeepJavaHome().getOrElse(false);
	}

	protected MigrationRunner runner() {
		return new MigrationRunner(new MigrationRunner.RunnerPaths(getRewriteInitScript().get().getAsFile().toPath(),
				getVerifyInitScript().get().getAsFile().toPath(), getRecipeLibs().get().getAsFile().toPath(),
				getPlaybookDir().get().getAsFile().toPath()), getGradleJvmArgs().getOrNull(), getLogger());
	}

}
