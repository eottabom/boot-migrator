package com.eottabom.rewrite.elasticsearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Elasticsearch RestClient → Rest5Client 전환에서 builder 콜백의 반환값을 없앤다.
 * <p>
 * RestClientBuilder 의 콜백은 받은 builder 를 돌려주는 형태였지만, Rest5ClientBuilder 의
 * {@code setHttpClientConfigCallback} / {@code setRequestConfigCallback} 은
 * {@code Consumer} 를 받는다. <pre>
 * setHttpClientConfigCallback(b -> { b.setX(..); return b; })   →  setHttpClientConfigCallback(b -> { b.setX(..); })
 * setHttpClientConfigCallback(b -> { return b.setX(..); })      →  setHttpClientConfigCallback(b -> { b.setX(..); })
 * </pre> 블록 마지막의 return 만 바꾼다. 중간에서 return 하는 복잡한 콜백은 그대로 두어 컴파일 에러로 드러나게 한다. 식
 * 람다({@code b -> b.setX(..)})와 메서드 참조는 Consumer 에 그대로 맞으므로 바꾸지 않는다.
 */
public class Rest5ClientCallbacksToConsumer extends Recipe {

	private static final List<String> BUILDERS = Arrays.asList("org.elasticsearch.client.RestClientBuilder",
			"co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder");

	private static final List<String> CALLBACKS = Arrays.asList("setHttpClientConfigCallback",
			"setRequestConfigCallback");

	@Override
	public String getDisplayName() {
		return "Rest5ClientBuilder 콜백을 Consumer 형태로";
	}

	@Override
	public String getDescription() {
		return "setHttpClientConfigCallback / setRequestConfigCallback 에 넘기는 람다 끝의 return builder 를 없앤다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
				J.Lambda l = super.visitLambda(lambda, ctx);
				Object parent = getCursor().getParentTreeCursor().getValue();
				if (!(parent instanceof J.MethodInvocation) || !isBuilderCallback((J.MethodInvocation) parent)
						|| !(l.getBody() instanceof J.Block)) {
					return l;
				}
				J.Block body = (J.Block) l.getBody();
				List<Statement> statements = body.getStatements();
				if (statements.isEmpty() || !(statements.get(statements.size() - 1) instanceof J.Return)) {
					return l;
				}
				J.Return ret = (J.Return) statements.get(statements.size() - 1);
				Expression value = ret.getExpression();
				List<Statement> updated = new ArrayList<>(statements.subList(0, statements.size() - 1));
				if (value instanceof J.MethodInvocation) {
					// return b.setX(..); → b.setX(..);
					updated.add(((J.MethodInvocation) value).withPrefix(ret.getPrefix()));
				}
				else if (!isLambdaParameter(l, value)) {
					return l;
				}
				return l.withBody(body.withStatements(updated));
			}

			private boolean isBuilderCallback(J.MethodInvocation m) {
				JavaType.Method type = m.getMethodType();
				return CALLBACKS.contains(m.getSimpleName()) && type != null
						&& BUILDERS.stream().anyMatch((b) -> TypeUtils.isOfClassType(type.getDeclaringType(), b));
			}

			private boolean isLambdaParameter(J.Lambda l, Expression value) {
				if (!(value instanceof J.Identifier) || l.getParameters().getParameters().size() != 1) {
					return false;
				}
				J param = l.getParameters().getParameters().get(0);
				String name = (param instanceof J.VariableDeclarations)
						? ((J.VariableDeclarations) param).getVariables().get(0).getSimpleName()
						: (param instanceof J.Identifier) ? ((J.Identifier) param).getSimpleName() : null;
				return ((J.Identifier) value).getSimpleName().equals(name);
			}
		};
	}

}
