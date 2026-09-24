package com.eottabom.rewrite.gradle;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * "group:artifact:version" 문자열 선언에서 버전만 지운다. (BOM 이 관리하는 의존성을 다시 BOM 에 맡길 때)
 * <p>
 * upstream {@code org.openrewrite.gradle.RemoveRedundantDependencyVersions} 는
 * groupPattern 을 줘도 "transitive 로 충족되는 direct 의존성" 을 통째로 지우는 로직이 함께 돌아서 (ex.
 * testAnnotationProcessor lombok, spring-boot-starter-cache 삭제) 쓸 수 없다.
 */
public class RemoveDependencyVersion extends Recipe {

	@Option(displayName = "Group", example = "org.springframework.restdocs")
	private final String groupId;

	public RemoveDependencyVersion(String groupId) {
		this.groupId = groupId;
	}

	public String getGroupId() {
		return this.groupId;
	}

	private static boolean isBom(String artifactId) {
		return "bom".equals(artifactId) || artifactId.endsWith("-bom") || artifactId.endsWith("-dependencies");
	}

	@Override
	public String getDisplayName() {
		return "의존성 버전 명시 제거";
	}

	@Override
	public String getDescription() {
		return "지정한 group 의 \"group:artifact:version\" 선언에서 버전을 지워 BOM 관리 버전을 따르게 한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		// build.gradle(Groovy)과 build.gradle.kts(Kotlin) 모두 J.MethodInvocation /
		// J.Literal / J.Assignment 로 읽힌다.
		// GroovyIsoVisitor 는 Groovy 파일만 받아서 kts 를 조용히 건너뛰므로 JavaIsoVisitor 를 쓴다
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
				J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
				// buildscript 의 classpath(플러그인)는 BOM 이 관리하지 않는다 (ex.
				// spring-boot-gradle-plugin)
				if ("classpath".equals(m.getSimpleName()) || m.getArguments().isEmpty()
						|| !(m.getArguments().get(0) instanceof J.Literal)) {
					return m;
				}
				J.Literal literal = (J.Literal) m.getArguments().get(0);
				if (!(literal.getValue() instanceof String)) {
					return m;
				}
				String[] parts = ((String) literal.getValue()).split(":", -1);
				// classifier 가 있는 선언(4개 이상)과 BOM 좌표(platform("...:bom:x.y"))는 건드리지 않는다
				if (parts.length != 3 || !RemoveDependencyVersion.this.groupId.equals(parts[0]) || parts[2].isEmpty()
						|| isBom(parts[1])) {
					return m;
				}
				String value = parts[0] + ":" + parts[1];
				String source = literal.getValueSource();
				String quote = (source != null && !source.isEmpty()) ? source.substring(0, 1) : "'";
				J.Literal updated = literal.withValue(value).withValueSource(quote + value + quote);
				return m.withArguments(ListUtils.mapFirst(m.getArguments(), (a) -> updated));
			}
		});
	}

}
