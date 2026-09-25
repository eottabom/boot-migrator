package com.eottabom.rewrite.gradle;

import java.nio.file.Path;
import java.util.Optional;

import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.text.PlainText;
import org.openrewrite.toml.TomlParser;
import org.openrewrite.toml.tree.Toml;

/**
 * gradle/*.versions.toml 파일. OpenRewrite 플러그인 버전에 따라 Toml 문서나 PlainText 로 파싱되므로 텍스트로 읽고
 * 쓴다.
 */
final class VersionCatalogSource {

	private VersionCatalogSource() {
	}

	static boolean isCatalog(SourceFile source) {
		Path path = source.getSourcePath();
		Path parent = path.getParent();
		return (source instanceof PlainText || source instanceof Toml.Document) && parent != null
				&& "gradle".equals(parent.getFileName().toString())
				&& path.getFileName().toString().endsWith(".versions.toml");
	}

	/** 기본 catalog (build 스크립트에서 libs 로 접근하는 파일) */
	static boolean isDefaultCatalog(SourceFile source) {
		return isCatalog(source) && "libs.versions.toml".equals(source.getSourcePath().getFileName().toString());
	}

	/** 텍스트가 바뀌었으면 같은 id, 경로, 마커로 다시 만든다 */
	static SourceFile withText(SourceFile source, String text, ExecutionContext ctx) {
		if (text.equals(source.printAll())) {
			return source;
		}
		if (source instanceof PlainText plainText) {
			return plainText.withText(text);
		}
		Optional<SourceFile> reparsed = new TomlParser().parse(ctx, text).findFirst();
		if (reparsed.isEmpty()) {
			return source;
		}
		SourceFile toml = reparsed.get().withSourcePath(source.getSourcePath());
		return toml.withId(source.getId()).withMarkers(source.getMarkers());
	}

}
