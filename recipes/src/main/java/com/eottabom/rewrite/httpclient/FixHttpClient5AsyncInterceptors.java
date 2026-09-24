package com.eottabom.rewrite.httpclient;

import java.util.ArrayList;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * upstream UpgradeApacheHttpClient_5 가 타입만 HttpClient 5 로 바꾸고 남겨두는 async 빌더 인터셉터 API 차이를
 * 맞춘다. (OpenSearch RestClient 의 setHttpClientConfigCallback 처럼 HttpClient 5
 * HttpAsyncClientBuilder 를 다루는 코드)
 * <ul>
 * <li>{@code addInterceptorLast/First(HttpResponseInterceptor)} →
 * {@code addResponseInterceptorLast/First}, HttpRequestInterceptor 는
 * {@code addRequestInterceptorLast/First}</li>
 * <li>HttpClient 5 인터셉터 람다는 인자가 3개다: {@code (response, context) -> } →
 * {@code (response, entity, context) -> } (incompatible parameter types in lambda
 * expression)</li>
 * </ul>
 */
public class FixHttpClient5AsyncInterceptors extends Recipe {

	private static final String RESPONSE_INTERCEPTOR = "org.apache.hc.core5.http.HttpResponseInterceptor";

	private static final String REQUEST_INTERCEPTOR = "org.apache.hc.core5.http.HttpRequestInterceptor";

	@Override
	public String getDisplayName() {
		return "HttpClient 5 async 인터셉터 API 보정";
	}

	@Override
	public String getDescription() {
		return "addInterceptorLast/First 를 HttpClient 5 이름으로 바꾸고, 인터셉터 람다에 entity 인자를 추가한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
				J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
				if (("addInterceptorLast".equals(m.getSimpleName()) || "addInterceptorFirst".equals(m.getSimpleName()))
						&& m.getArguments().size() == 1) {
					String suffix = m.getSimpleName().endsWith("Last") ? "Last" : "First";
					JavaType argType = interceptorType(m.getArguments().get(0));
					if (TypeUtils.isOfClassType(argType, RESPONSE_INTERCEPTOR)) {
						return m.withName(m.getName().withSimpleName("addResponseInterceptor" + suffix));
					}
					if (TypeUtils.isOfClassType(argType, REQUEST_INTERCEPTOR)) {
						return m.withName(m.getName().withSimpleName("addRequestInterceptor" + suffix));
					}
				}
				return m;
			}

			@Override
			public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
				J.Lambda l = super.visitLambda(lambda, ctx);
				J parent = getCursor().getParentTreeCursor().getValue();
				JavaType target = (parent instanceof J.TypeCast) ? ((J.TypeCast) parent).getClazz().getTree().getType()
						: l.getType();
				boolean interceptor = TypeUtils.isOfClassType(target, RESPONSE_INTERCEPTOR)
						|| TypeUtils.isOfClassType(target, REQUEST_INTERCEPTOR);
				if (!interceptor || l.getParameters().getParameters().size() != 2) {
					return l;
				}
				// (response, context) -> ... 에 가운데 entity 인자를 넣는다
				List<JRightPadded<J>> params = new ArrayList<>(l.getParameters().getPadding().getParameters());
				J first = params.get(0).getElement();
				J entity = (first instanceof J.Identifier)
						? ((J.Identifier) first).withId(Tree.randomId())
							.withSimpleName("entity")
							.withPrefix(Space.SINGLE_SPACE)
						: new J.Identifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, new ArrayList<>(),
								"entity", null, null);
				params.add(1, JRightPadded.build(entity));
				return l.withParameters(l.getParameters().getPadding().withParameters(params));
			}

			private JavaType interceptorType(Expression arg) {
				return (arg instanceof J.TypeCast) ? ((J.TypeCast) arg).getClazz().getTree().getType() : arg.getType();
			}
		};
	}

}
