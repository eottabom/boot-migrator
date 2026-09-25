package com.eottabom.migration.exec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.eottabom.migration.exec.MigrationRunner.RunnerPaths;
import com.eottabom.migration.inspect.ProjectInspector;
import com.eottabom.migration.model.Stage;
import com.eottabom.migration.recipe.GeneratedRecipe;
import com.eottabom.migration.recipe.GeneratedRecipe.Generated;
import com.eottabom.migration.recipe.ProjectRecipes;
import org.gradle.api.GradleException;

/**
 * 모든 단계의 변경을 미리 본다. 다음 단계의 변경은 앞 단계를 적용해야 알 수 있어서, 임시 git worktree 에 단계를 차례로 적용하고 단계별
 * patch 만 남긴 뒤 worktree 를 지운다. 대상 프로젝트의 작업 트리는 바뀌지 않는다.
 */
final class StagePreview {

	private final RunnerPaths paths;

	private final BuildTool.Factory buildTools;

	private final ProjectInspector inspector;

	private final RunnerConsole console;

	StagePreview(RunnerPaths paths, BuildTool.Factory buildTools, ProjectInspector inspector, RunnerConsole console) {
		this.paths = paths;
		this.buildTools = buildTools;
		this.inspector = inspector;
		this.console = console;
	}

	/**
	 * @param order 첫 단계 번호 - 1 (지난 기록 뒤에 이어서 붙인다)
	 */
	void run(Path projectDir, MigrationWorkspace ws, List<Stage> stages, int order, ProjectRecipes projectRecipes,
			String javaHome) {
		String projectName = projectDir.getFileName().toString();
		Git git = new Git(projectDir);
		Path tempDir = createTempDir();
		Path worktree = tempDir.resolve(projectName);
		if (!git.addWorktree(worktree)) {
			throw new GradleException("preview 용 임시 worktree 를 만들지 못했다 → " + worktree);
		}
		try {
			BuildTool gradle = this.buildTools.create(worktree, javaHome);
			Git worktreeGit = new Git(worktree);
			String previous = worktreeGit.head();
			int number = order;
			for (Stage stage : stages) {
				number++;
				String tag = stage.tag(number);
				Generated generated = GeneratedRecipe.write(worktree, projectName, stage, tag, projectRecipes);
				this.console.step("[" + stage.name() + "] " + stage.recipe()
						+ RunnerConsole.projectRecipeSuffix(projectRecipes, stage) + " (preview)");
				if (!gradle.rewrite(ws.file(tag + ".rewrite.log"), "rewriteRun", generated.name(),
						this.paths.rewriteInit(), this.paths.recipeLibs(), generated.file())) {
					throw new GradleException("preview 실패 → " + ws.file(tag + ".rewrite.log"));
				}
				String current = worktreeGit.commitAll("preview " + stage.name());
				Path patch = ws.file(tag + ".dry.patch");
				if (current == null || !worktreeGit.diffTrees(previous, current, patch)) {
					throw new GradleException("preview patch 를 만들지 못했다 → " + patch);
				}
				previous = current;
				this.console.line("   Boot {}, {} files → {}", RunnerConsole.orQ(this.inspector.bootVersion(worktree)),
						MigrationWorkspace.countMatches(patch, "^diff --git"), patch);
			}
		}
		finally {
			git.removeWorktree(worktree);
			deleteQuietly(tempDir);
		}
		this.console.line("   (컴파일과 테스트는 하지 않는다. 커밋되지 않은 변경은 preview 에 들어가지 않는다)");
	}

	private static Path createTempDir() {
		try {
			return Files.createTempDirectory("boot-migrator-preview");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void deleteQuietly(Path dir) {
		try {
			Files.deleteIfExists(dir);
		}
		catch (IOException ex) {
			// worktree remove 가 남긴 빈 디렉토리는 임시 디렉토리라 그대로 둬도 된다
		}
	}

}
