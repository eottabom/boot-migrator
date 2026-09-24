package com.eottabom.rewrite.gradle;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.DependencyVersionSelector;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.gradle.marker.GradleSettings;
import org.openrewrite.maven.MavenDownloadingException;
import org.openrewrite.maven.table.MavenMetadataFailures;
import org.openrewrite.maven.tree.GroupArtifact;
import org.openrewrite.maven.tree.GroupArtifactVersion;
import org.openrewrite.semver.ExactVersion;
import org.openrewrite.semver.LatestRelease;
import org.openrewrite.semver.Semver;
import org.openrewrite.text.PlainText;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.toml.tree.Toml;

/**
 * upstream 의 의존성/플러그인 버전 변경을 Gradle version catalog 에도 적용한다.
 * <p>
 * upstream {@code UpgradeDependencyVersion}, {@code UpgradePluginVersion},
 * {@code ChangeDependency} 는 빌드 스크립트의 선언만 바꾸고 {@code gradle/*.versions.toml} 은 건드리지 않는다.
 * catalog 를 쓰는 프로젝트는 {@code libs.plugins.spring.boot} 의 버전이 그대로라 Boot 버전부터 올라가지 않는다.
 * <p>
 * 규칙은 단계 레시피 트리에서 생성한다 (version-catalog-steps.yml). 버전 선택은 upstream 과 같은
 * {@link DependencyVersionSelector} 로 대상 프로젝트의 저장소에서 한다.
 */
public class UpgradeVersionCatalog extends ScanningRecipe<UpgradeVersionCatalog.Accumulator> {

	@Option(displayName = "Rules", description = "dependency / plugin / change 규칙. 형식은 VersionCatalogEditor.Rule",
			example = "plugin org.springframework.boot 3.4.x")
	private final List<String> rules;

	private final transient MavenMetadataFailures metadataFailures = new MavenMetadataFailures(this);

	public UpgradeVersionCatalog(List<String> rules) {
		this.rules = rules;
	}

	public List<String> getRules() {
		return this.rules;
	}

	@Override
	public String getDisplayName() {
		return "Gradle version catalog 버전 정렬";
	}

	@Override
	public String getDescription() {
		return "upstream 이 빌드 스크립트에 적용하는 의존성/플러그인 버전 변경을 gradle/*.versions.toml 에도 적용한다.";
	}

	@Override
	public Accumulator getInitialValue(ExecutionContext ctx) {
		return new Accumulator();
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
		return new TreeVisitor<Tree, ExecutionContext>() {
			@Override
			public Tree visit(Tree tree, ExecutionContext ctx) {
				if (tree instanceof SourceFile source) {
					Optional<GradleProject> project = source.getMarkers().findFirst(GradleProject.class);
					// 루트 프로젝트(경로가 가장 짧은 빌드 파일)의 저장소를 쓴다
					if (project.isPresent()
							&& (acc.project == null || source.getSourcePath().getNameCount() < acc.projectDepth)) {
						acc.project = project.get();
						acc.projectDepth = source.getSourcePath().getNameCount();
					}
					source.getMarkers().findFirst(GradleSettings.class).ifPresent((s) -> acc.settings = s);
				}
				return tree;
			}
		};
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		List<VersionCatalogEditor.Rule> parsed = this.rules.stream().map(VersionCatalogEditor.Rule::parse).toList();
		return new TreeVisitor<Tree, ExecutionContext>() {
			@Override
			public Tree visit(Tree tree, ExecutionContext ctx) {
				if (!(tree instanceof SourceFile source) || !isCatalog(source.getSourcePath())
						|| !(tree instanceof PlainText || tree instanceof Toml.Document)) {
					return tree;
				}
				String before = source.printAll();
				String after = VersionCatalogEditor.apply(before, parsed, resolver(acc, ctx));
				if (after.equals(before)) {
					return tree;
				}
				if (tree instanceof PlainText text) {
					return text.withText(after);
				}
				Optional<SourceFile> reparsed = new TomlParser().parse(ctx, after).findFirst();
				if (reparsed.isEmpty()) {
					return tree;
				}
				SourceFile toml = reparsed.get().withSourcePath(source.getSourcePath());
				return toml.withId(source.getId()).withMarkers(source.getMarkers());
			}
		};
	}

	static boolean isCatalog(Path path) {
		Path parent = path.getParent();
		return parent != null && "gradle".equals(parent.getFileName().toString())
				&& path.getFileName().toString().endsWith(".versions.toml");
	}

	private VersionCatalogEditor.Resolver resolver(Accumulator acc, ExecutionContext ctx) {
		return (group, artifact, current, newVersion, versionPattern, plugin) -> {
			// Semver.isVersion 은 3.4.x 같은 패턴도 true 라서 쓰지 않는다
			if (Semver.validate(newVersion, versionPattern).getValue() instanceof ExactVersion) {
				return (current == null || isNewer(current, newVersion)) ? newVersion : null;
			}
			if (acc.project == null) {
				return null;
			}
			DependencyVersionSelector selector = new DependencyVersionSelector(this.metadataFailures, acc.project,
					acc.settings);
			String configuration = plugin ? "classpath" : null;
			try {
				String selected = (current != null)
						? selector.select(new GroupArtifactVersion(group, artifact, current), configuration, newVersion,
								versionPattern, ctx)
						: selector.select(new GroupArtifact(group, artifact), configuration, newVersion, versionPattern,
								ctx);
				return (selected != null && (current == null || isNewer(current, selected))) ? selected : null;
			}
			catch (MavenDownloadingException ex) {
				return null;
			}
		};
	}

	private static boolean isNewer(String current, String candidate) {
		return new LatestRelease(null).compare(null, current, candidate) < 0;
	}

	public static class Accumulator {

		GradleProject project;

		int projectDepth;

		GradleSettings settings;

	}

}
