package com.eottabom.rewrite.gradle;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * 이번 실행에서 레시피가 빌드 스크립트에 문자열로 추가한 의존성을 version catalog 항목으로 옮긴다.
 * <p>
 * upstream 과 커스텀 레시피는 의존성을 {@code "group:artifact"} 문자열로 추가한다. catalog 를 쓰는 프로젝트에서는 선언
 * 방식이 섞인다. 스캔 단계에서 원본 빌드 스크립트에 있던 좌표를 기록해 두고, 편집 단계에서 원본에 없던 좌표 문자열만 {@code libs.xxx} 로
 * 바꾼 뒤 catalog 에 항목을 더한다. 사용자가 원래 문자열로 쓴 의존성은 건드리지 않는다.
 * <p>
 * gradle/libs.versions.toml 이 있고 빌드 스크립트가 이미 {@code libs.} 를 쓰는 경우에만 적용한다. 다른 레시피가 추가한 뒤에
 * 돌아야 해서 단계의 마지막(CommonMigrationFixes 끝)에 둔다.
 */
public class DeclareAddedDependenciesInVersionCatalog extends Recipe {

	/** 빌드 스크립트 레시피가 정한 alias 와 좌표, 바꾼 사이클. catalog 레시피가 읽는다 */
	static final String ADDED = DeclareAddedDependenciesInVersionCatalog.class.getName() + ".added";

	private static final Pattern COORDINATES = Pattern.compile("[\\w.\\-]+:[\\w.\\-]+(:[\\w.+\\-]+)?");

	private static final Pattern QUOTED_COORDINATES = Pattern.compile("[\"'](" + COORDINATES.pattern() + ")[\"']");

	@Override
	public String getDisplayName() {
		return "새로 추가된 의존성을 version catalog 로 선언";
	}

	@Override
	public String getDescription() {
		return "레시피가 문자열로 추가한 의존성을 libs.versions.toml 항목과 libs 접근자로 바꾼다. 원래 있던 문자열 선언은 그대로 둔다.";
	}

	@Override
	public List<Recipe> getRecipeList() {
		return List.of(new UseCatalogAccessors(), new AddCatalogEntries());
	}

	static String accessor(String alias) {
		return "libs." + alias.replace('-', '.').replace('_', '.');
	}

	@SuppressWarnings("unchecked")
	static Map<String, Added> added(ExecutionContext ctx) {
		return ctx.computeMessageIfAbsent(ADDED, (key) -> new LinkedHashMap<>());
	}

	/**
	 * @param cycle 빌드 스크립트를 바꾼 사이클. catalog 는 파일 처리 순서와 상관없이 항상 그다음 사이클에 고친다
	 */
	record Added(String coordinates, int cycle) {
	}

	/**
	 * @param aliases catalog 의 모듈과 alias (새로 정한 것 포함)
	 * @param original 빌드 스크립트별 원본 좌표
	 * @param usingCatalog libs 접근자를 이미 쓰는 빌드 스크립트
	 */
	record Accumulator(Map<String, String> aliases, Map<Path, Set<String>> original, Set<Path> usingCatalog,
			boolean[] hasCatalog) {

		Accumulator() {
			this(new HashMap<>(), new HashMap<>(), new HashSet<>(), new boolean[1]);
		}

		/** 모듈이 catalog 에 있으면 그 alias, 없으면 artifact 이름 (다른 모듈이 쓰면 group 마지막 이름을 붙인다) */
		String aliasFor(String group, String artifact) {
			String module = group + ":" + artifact;
			String existing = this.aliases.get(module);
			if (existing != null) {
				return existing;
			}
			String alias = artifact;
			if (this.aliases.containsValue(alias)) {
				alias = group.substring(group.lastIndexOf('.') + 1) + "-" + artifact;
			}
			this.aliases.put(module, alias);
			return alias;
		}

	}

	static class UseCatalogAccessors extends ScanningRecipe<Accumulator> {

		@Override
		public String getDisplayName() {
			return "새로 추가된 의존성 문자열을 libs 접근자로";
		}

		@Override
		public String getDescription() {
			return "원본 빌드 스크립트에 없던 의존성 좌표 문자열을 version catalog 접근자로 바꾼다.";
		}

		/** 바꾼 경우에만 한 사이클 더 돌아 catalog 항목을 더한다 */
		@Override
		public boolean causesAnotherCycle() {
			return true;
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
					if (!(tree instanceof SourceFile source)) {
						return tree;
					}
					if (VersionCatalogSource.isDefaultCatalog(source)) {
						acc.hasCatalog()[0] = true;
						VersionCatalogEditor.libraryAliases(source.printAll()).forEach(acc.aliases()::putIfAbsent);
					}
					else if (isBuildScript(source.getSourcePath())) {
						String text = source.printAll();
						if (text.contains("libs.")) {
							acc.usingCatalog().add(source.getSourcePath());
						}
						Set<String> coordinates = new HashSet<>();
						Matcher m = QUOTED_COORDINATES.matcher(text);
						while (m.find()) {
							coordinates.add(m.group(1));
						}
						acc.original().put(source.getSourcePath(), coordinates);
					}
					return tree;
				}
			};
		}

		@Override
		public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
			return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
				@Override
				public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
					J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
					Path path = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath();
					if (!acc.hasCatalog()[0] || !acc.usingCatalog().contains(path) || m.getArguments().size() != 1
							|| !(m.getArguments().get(0) instanceof J.Literal literal)
							|| !(literal.getValue() instanceof String coordinates)
							|| !COORDINATES.matcher(coordinates).matches()
							|| acc.original().getOrDefault(path, Set.of()).contains(coordinates)
							|| !inDependenciesBlock(getCursor())) {
						return m;
					}
					String[] gav = coordinates.split(":");
					String alias = acc.aliasFor(gav[0], gav[1]);
					added(ctx).putIfAbsent(alias, new Added(coordinates, ctx.getCycle()));
					J.Identifier libs = new J.Identifier(Tree.randomId(), literal.getPrefix(), literal.getMarkers(),
							List.of(), accessor(alias), null, null);
					// Groovy 의 괄호 없는 호출 표시(OmitParentheses)는 인자 마커에 있어서 원래 문자열의 마커를 그대로
					// 쓴다
					return m.withArguments(List.of(libs));
				}
			});
		}

		private static boolean isBuildScript(Path path) {
			String name = path.getFileName().toString();
			return name.equals("build.gradle") || name.equals("build.gradle.kts");
		}

		/** buildscript 블록 밖의 dependencies 블록 안 */
		private static boolean inDependenciesBlock(Cursor cursor) {
			boolean dependencies = false;
			for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
				if (c.getValue() instanceof J.MethodInvocation invocation) {
					String name = invocation.getSimpleName();
					if (name.equals("buildscript") || name.equals("constraints")) {
						return false;
					}
					dependencies |= name.equals("dependencies");
				}
			}
			return dependencies;
		}

	}

	static class AddCatalogEntries extends Recipe {

		@Override
		public String getDisplayName() {
			return "새로 추가된 의존성을 libs.versions.toml 에 선언";
		}

		@Override
		public String getDescription() {
			return "빌드 스크립트에서 libs 접근자로 바꾼 의존성을 version catalog 의 [libraries] 에 더한다.";
		}

		@Override
		public TreeVisitor<?, ExecutionContext> getVisitor() {
			return new TreeVisitor<Tree, ExecutionContext>() {
				@Override
				public Tree visit(Tree tree, ExecutionContext ctx) {
					if (!(tree instanceof SourceFile source) || !VersionCatalogSource.isDefaultCatalog(source)) {
						return tree;
					}
					Map<String, String> libraries = new LinkedHashMap<>();
					added(ctx).forEach((alias, added) -> {
						if (added.cycle() < ctx.getCycle()) {
							libraries.put(alias, added.coordinates());
						}
					});
					return libraries.isEmpty() ? tree : VersionCatalogSource.withText(source,
							VersionCatalogEditor.addLibraries(source.printAll(), libraries), ctx);
				}
			};
		}

	}

}
