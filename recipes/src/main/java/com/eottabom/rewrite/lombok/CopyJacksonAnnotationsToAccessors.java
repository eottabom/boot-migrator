package com.eottabom.rewrite.lombok;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.text.PlainText;
import org.openrewrite.text.PlainTextParser;
import org.openrewrite.text.PlainTextVisitor;

/**
 * 루트 lombok.config 에 {@code lombok.copyJacksonAnnotationsToAccessors = true} 를 넣는다.
 * <p>
 * Lombok 1.18.16 ~ 1.18.38 은 필드의 {@code @JsonProperty} 등을 생성한 getter/setter 에도 복사했지만
 * 1.18.40 부터는 복사하지 않는다. 그래서 {@code @JsonProperty("isShow") boolean isShow} 는 필드(isShow)와
 * getter isShow()(Jackson 이름 규칙으로 show)가 따로 잡혀 JSON 에 {@code isShow} 와 {@code show} 가 둘 다
 * 나간다. 이 설정으로 이전 동작을 유지한다.
 * <p>
 * Lombok 과 Jackson 어노테이션을 함께 쓰는 소스가 있을 때만 동작한다. lombok.config 가 없으면 만들고, 있으면 한 줄 추가하고, 이미
 * 이 키가 있으면(값이 false 여도) 그대로 둔다. 구조는 upstream rewrite-jackson 의 LombokJacksonizedConfig 와
 * 같다.
 * <p>
 * 루트 .gitignore 가 lombok.config 를 무시하고 있으면(예전 freefair 플러그인이 모듈마다 만들던 파일을 무시하던 설정)
 * {@code !/lombok.config} 를 추가해 루트 파일만 커밋되게 한다. 커밋되지 않으면 CI 와 다른 PC 에서 설정이 빠진다.
 */
public class CopyJacksonAnnotationsToAccessors extends ScanningRecipe<CopyJacksonAnnotationsToAccessors.Accumulator> {

	private static final String LOMBOK_CONFIG = "lombok.config";

	private static final String KEY = "lombok.copyJacksonAnnotationsToAccessors";

	private static final String ENTRY = KEY + " = true";

	private static final String GITIGNORE = ".gitignore";

	private static final String UNIGNORE = "!/lombok.config";

	private static final Pattern IGNORES_CONFIG = Pattern.compile("(?m)^\\s*(\\*\\*/|/)?lombok\\.config\\s*$");

	public static class Accumulator {

		boolean usesLombokWithJackson;

		boolean configExists;

	}

	@Override
	public String getDisplayName() {
		return "lombok.config: Jackson 어노테이션을 accessor 에 복사";
	}

	@Override
	public String getDescription() {
		return "Lombok 1.18.40+ 에서 @JsonProperty 가 getter 에 복사되지 않아 JSON 속성이 중복되는 문제를 lombok.copyJacksonAnnotationsToAccessors = true 로 막는다.";
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
				if (tree instanceof SourceFile
						&& LOMBOK_CONFIG.equals(((SourceFile) tree).getSourcePath().toString())) {
					acc.configExists = true;
				}
				else if (tree instanceof J.CompilationUnit && !acc.usesLombokWithJackson) {
					acc.usesLombokWithJackson = usesLombokWithJackson((J.CompilationUnit) tree);
				}
				return tree;
			}
		};
	}

	@Override
	public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
		if (!acc.usesLombokWithJackson || acc.configExists) {
			return Collections.emptyList();
		}
		return PlainTextParser.builder()
			.build()
			.parse(ENTRY + "\n")
			.map((s) -> (SourceFile) s.withSourcePath(Paths.get(LOMBOK_CONFIG)))
			.collect(Collectors.toList());
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		return new PlainTextVisitor<ExecutionContext>() {
			@Override
			public PlainText visitText(PlainText text, ExecutionContext ctx) {
				if (!acc.usesLombokWithJackson) {
					return text;
				}
				String path = text.getSourcePath().toString();
				if (GITIGNORE.equals(path)) {
					return unignoreRootConfig(text);
				}
				if (!LOMBOK_CONFIG.equals(path) || text.getText().contains(KEY)) {
					return text;
				}
				// 파일 끝 줄바꿈 여부는 원래 파일을 따른다
				String content = text.getText();
				if (content.isEmpty()) {
					return text.withText(ENTRY + "\n");
				}
				return text.withText(content.endsWith("\n") ? content + ENTRY + "\n" : content + "\n" + ENTRY);
			}
		};
	}

	private static PlainText unignoreRootConfig(PlainText gitignore) {
		String content = gitignore.getText();
		if (!IGNORES_CONFIG.matcher(content).find() || content.contains(UNIGNORE)) {
			return gitignore;
		}
		String lines = "# 루트 lombok.config 는 커밋한다 (lombok.copyJacksonAnnotationsToAccessors)\n" + UNIGNORE;
		// 파일 끝 줄바꿈 여부는 원래 파일을 따른다
		return gitignore.withText(content.endsWith("\n") ? content + lines + "\n" : content + "\n" + lines);
	}

	private static boolean usesLombokWithJackson(J.CompilationUnit cu) {
		boolean lombok = false;
		boolean jackson = false;
		for (JavaType type : cu.getTypesInUse().getTypesInUse()) {
			if (type instanceof JavaType.FullyQualified) {
				String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
				lombok |= fqn.startsWith("lombok.");
				jackson |= fqn.startsWith("com.fasterxml.jackson.annotation.");
			}
		}
		return lombok && jackson;
	}

}
