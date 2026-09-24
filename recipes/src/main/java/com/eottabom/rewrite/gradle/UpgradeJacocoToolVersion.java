package com.eottabom.rewrite.gradle;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * build.gradle 의 {@code jacoco { toolVersion = "0.8.7" }} 를 지정 버전 이상으로 올린다.
 * <p>
 * upstream UpgradeJaCoCo 는 org.jacoco 의존성만 올리고 jacoco 확장의 toolVersion 은 건드리지 않는다. JaCoCo
 * 는 새 Java 클래스 파일 버전을 읽으려면 그에 맞는 버전이 필요하다 (Java 21: 0.8.11+, Java 25: 0.8.14+). 지정 버전보다
 * 이미 높으면 그대로 둔다.
 */
public class UpgradeJacocoToolVersion extends Recipe {

	@Option(displayName = "Version", example = "0.8.15")
	private final String version;

	public UpgradeJacocoToolVersion(String version) {
		this.version = version;
	}

	public String getVersion() {
		return this.version;
	}

	@Override
	public String getDisplayName() {
		return "JaCoCo toolVersion 업그레이드";
	}

	@Override
	public String getDescription() {
		return "jacoco { toolVersion = \"x\" } 를 지정 버전 이상으로 올린다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		// build.gradle(Groovy)과 build.gradle.kts(Kotlin) 모두 J.MethodInvocation /
		// J.Literal / J.Assignment 로 읽힌다.
		// GroovyIsoVisitor 는 Groovy 파일만 받아서 kts 를 조용히 건너뛰므로 JavaIsoVisitor 를 쓴다
		return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext ctx) {
				J.Assignment a = super.visitAssignment(assignment, ctx);
				if (!(a.getVariable() instanceof J.Identifier)
						|| !"toolVersion".equals(((J.Identifier) a.getVariable()).getSimpleName())
						|| !(a.getAssignment() instanceof J.Literal) || !insideJacocoBlock()) {
					return a;
				}
				J.Literal literal = (J.Literal) a.getAssignment();
				if (!(literal.getValue() instanceof String)
						|| compare((String) literal.getValue(), UpgradeJacocoToolVersion.this.version) >= 0) {
					return a;
				}
				String source = literal.getValueSource();
				String quote = (source != null && !source.isEmpty()) ? source.substring(0, 1) : "\"";
				return a.withAssignment(literal.withValue(UpgradeJacocoToolVersion.this.version)
					.withValueSource(quote + UpgradeJacocoToolVersion.this.version + quote));
			}

			private boolean insideJacocoBlock() {
				J.MethodInvocation enclosing = getCursor().firstEnclosing(J.MethodInvocation.class);
				return enclosing != null && "jacoco".equals(enclosing.getSimpleName());
			}
		});
	}

	private static int compare(String a, String b) {
		String[] x = a.split("\\.");
		String[] y = b.split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int xi = (i < x.length) ? parse(x[i]) : 0;
			int yi = (i < y.length) ? parse(y[i]) : 0;
			if (xi != yi) {
				return Integer.compare(xi, yi);
			}
		}
		return 0;
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D.*", ""));
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

}
