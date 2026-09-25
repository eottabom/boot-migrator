package com.eottabom.migration.exec;

import java.nio.file.Path;
import java.util.List;

import com.eottabom.migration.model.MigrationPlan;
import com.eottabom.migration.model.ProjectModel;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.recipe.ProjectRecipes;
import com.eottabom.migration.recipe.ProjectRecipes.Phase;
import com.eottabom.migration.recipe.ProjectRecipes.ProjectRecipe;
import org.gradle.api.logging.Logger;

/**
 * 러너의 콘솔 출력. 단계 제목은 {@code >>}, 오류는 {@code !!} 로 시작한다.
 */
final class RunnerConsole {

	private final Logger logger;

	RunnerConsole(Logger logger) {
		this.logger = logger;
	}

	void project(ProjectModel project, String javaHome) {
		this.step("프로젝트 : " + project.dir());
		this.line("   Boot     : {}", orQ(project.bootVersion()));
		this.line("   Gradle   : {}", orQ(project.gradleVersion()));
		this.line("   Java     : {}", orQ(project.javaVersion()));
		this.line("   JAVA_HOME: {}", orDefault(javaHome));
		this.line("   git      : {}", !project.git() ? "아님" : project.dirty() ? "커밋되지 않은 변경 있음" : "깨끗함");
	}

	void projectRecipes(Path projectDir, ProjectRecipes projectRecipes) {
		if (projectRecipes.files().isEmpty()) {
			return;
		}
		this.step("프로젝트 레시피 ("
				+ String.join(", ",
						projectRecipes.files().stream().map((f) -> projectDir.relativize(f).toString()).toList())
				+ ")");
		if (projectRecipes.recipes().isEmpty()) {
			this.line("   migration-stage 태그가 붙은 레시피가 없다 (태그가 없는 레시피는 다른 레시피가 참조할 때만 쓰인다)");
		}
		for (ProjectRecipe recipe : projectRecipes.recipes()) {
			this.line("   {}  단계 {} / {}", recipe.name(), recipe.stages(),
					(recipe.phase() == Phase.BEFORE) ? "before" : "after");
		}
	}

	void targetLine(MigrationPlan plan) {
		var line = plan.targetLine();
		this.line(
				"   호환성   : Java {} ~ {} / Gradle {} / Spring Framework {} / Spring Cloud {} ({}+) / Spring Cloud AWS {}",
				line.javaMin(), line.javaMax(), line.gradleRange(), line.framework(), line.springCloudTrain(),
				line.springCloudSince(), line.springCloudAws());
	}

	void notes(MigrationPlan plan) {
		plan.notes().forEach((n) -> this.line("   참고     : {}", n));
	}

	static String projectRecipeSuffix(ProjectRecipes projectRecipes, Stage stage) {
		List<String> before = projectRecipes.names(stage.issueKey(), Phase.BEFORE);
		List<String> after = projectRecipes.names(stage.issueKey(), Phase.AFTER);
		if (before.isEmpty() && after.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder("  + 프로젝트");
		if (!before.isEmpty()) {
			out.append(" before ").append(before);
		}
		if (!after.isEmpty()) {
			out.append(" after ").append(after);
		}
		return out.toString();
	}

	void step(String message) {
		this.logger.lifecycle("");
		this.logger.lifecycle(">> {}", message);
	}

	void line(String format, Object... args) {
		this.logger.lifecycle(format, args);
	}

	void error(String message) {
		this.logger.error("!! {}", message);
	}

	static String orQ(Object value) {
		return (value != null) ? value.toString() : "?";
	}

	static String orDefault(String value) {
		return (value != null) ? value : "default";
	}

}
