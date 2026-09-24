package com.eottabom.rewrite.querydsl;

import java.util.ArrayList;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.Statement;

/**
 * querydsl-apt(jakarta) 를 쓰는 dependencies 블록에 아래 annotationProcessor 가 없으면 apt 선언 바로 위에
 * 추가한다. <pre>
 *   annotationProcessor "jakarta.annotation:jakarta.annotation-api"
 *   annotationProcessor "jakarta.persistence:jakarta.persistence-api"
 * </pre> upstream {@code RemoveJakartaAnnotationDependencyWhenManagedBySpringBoot} 가
 * javax 코드 기준으로 판단해서 annotationProcessor 의 jakarta.annotation-api 를 지우는 경우가 있다.
 * {@code org.openrewrite.gradle.AddDependency} 는 같은 실행 안에서 갱신되지 않은 Gradle 모델을 보고 "이미 있다"
 * 고 판단해서 복구하지 못하므로, LST 를 직접 확인한다.
 */
public class EnsureQuerydslAptJakartaApis extends Recipe {

	private static final String ANNOTATION_PROCESSOR = "annotationProcessor";

	private static final String[] REQUIRED = { "jakarta.annotation:jakarta.annotation-api",
			"jakarta.persistence:jakarta.persistence-api" };

	@Override
	public String getDisplayName() {
		return "QueryDSL APT 용 jakarta API annotationProcessor 보장";
	}

	@Override
	public String getDescription() {
		return "querydsl-apt(jakarta) 가 Q-class 생성에 필요로 하는 jakarta.annotation-api / jakarta.persistence-api 를 "
				+ "annotationProcessor 에 추가한다. 이미 있으면 아무것도 하지 않는다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new IsBuildGradle<>(), new GroovyIsoVisitor<ExecutionContext>() {
			@Override
			public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
				J.Block b = super.visitBlock(block, ctx);

				List<Statement> statements = b.getStatements();
				int aptIndex = -1;
				J.MethodInvocation apt = null;
				List<String> present = new ArrayList<>();
				for (int i = 0; i < statements.size(); i++) {
					J.MethodInvocation m = asMethodInvocation(statements.get(i));
					if (m == null || !ANNOTATION_PROCESSOR.equals(m.getSimpleName())) {
						continue;
					}
					String notation = dependencyNotation(m);
					if (notation == null) {
						continue;
					}
					if (apt == null && notation.startsWith("com.querydsl:querydsl-apt:")
							&& notation.endsWith(":jakarta")) {
						apt = m;
						aptIndex = i;
					}
					for (String required : REQUIRED) {
						if (notation.startsWith(required)) {
							present.add(required);
						}
					}
				}
				if (apt == null) {
					return b;
				}

				List<Statement> toAdd = new ArrayList<>();
				for (String required : REQUIRED) {
					if (!present.contains(required)) {
						toAdd.add(copyWithNotation(apt, required));
					}
				}
				if (toAdd.isEmpty()) {
					return b;
				}

				// apt 선언의 prefix(앞 줄의 trailing comment 포함)는 첫 번째로 추가되는 줄이 가져가고,
				// 나머지 줄들은 줄바꿈 + 들여쓰기만 갖게 한다.
				Space aptPrefix = statements.get(aptIndex).getPrefix();
				Space lineBreak = Space.format("\n" + indentOf(aptPrefix));
				List<Statement> updated = new ArrayList<>(statements.subList(0, aptIndex));
				for (int i = 0; i < toAdd.size(); i++) {
					updated.add(toAdd.get(i).withPrefix((i == 0) ? aptPrefix : lineBreak));
				}
				updated.add(statements.get(aptIndex).withPrefix(lineBreak));
				updated.addAll(statements.subList(aptIndex + 1, statements.size()));
				return b.withStatements(updated);
			}
		});
	}

	private static J.MethodInvocation asMethodInvocation(Statement statement) {
		if (statement instanceof J.MethodInvocation) {
			return (J.MethodInvocation) statement;
		}
		// Groovy closure 의 마지막 문장은 암묵적 return 으로 감싸진다
		if (statement instanceof J.Return && ((J.Return) statement).getExpression() instanceof J.MethodInvocation) {
			return (J.MethodInvocation) ((J.Return) statement).getExpression();
		}
		return null;
	}

	private static String dependencyNotation(J.MethodInvocation m) {
		if (m.getArguments().isEmpty()) {
			return null;
		}
		Expression first = m.getArguments().get(0);
		if (first instanceof J.Literal && ((J.Literal) first).getValue() instanceof String) {
			return (String) ((J.Literal) first).getValue();
		}
		if (first instanceof G.GString) {
			// "${version}" 부분은 버전 자리이므로 판별에는 상수 조각만 있으면 된다
			StringBuilder sb = new StringBuilder();
			for (J part : ((G.GString) first).getStrings()) {
				sb.append((part instanceof J.Literal) ? String.valueOf(((J.Literal) part).getValue()) : "${}");
			}
			return sb.toString();
		}
		return null;
	}

	private static Statement copyWithNotation(J.MethodInvocation apt, String notation) {
		Expression first = apt.getArguments().get(0);
		String quote = "\"";
		if (first instanceof J.Literal && ((J.Literal) first).getValueSource() != null
				&& ((J.Literal) first).getValueSource().startsWith("'")) {
			quote = "'";
		}
		J.Literal literal = new J.Literal(Tree.randomId(), first.getPrefix(), first.getMarkers(), notation,
				quote + notation + quote, null, org.openrewrite.java.tree.JavaType.Primitive.String);
		List<Expression> args = new ArrayList<>();
		args.add(literal);
		return apt.withId(Tree.randomId()).withArguments(args);
	}

	private static String indentOf(Space prefix) {
		String ws = prefix.getWhitespace();
		int nl = ws.lastIndexOf('\n');
		return (nl >= 0) ? ws.substring(nl + 1) : ws;
	}

}
