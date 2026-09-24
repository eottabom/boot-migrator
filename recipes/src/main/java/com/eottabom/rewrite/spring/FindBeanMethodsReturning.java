package com.eottabom.rewrite.spring;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

/**
 * 반환 타입이 지정한 타입(하위 타입 포함)인 {@code @Bean} 메서드를 표시한다.
 * <p>
 * upstream {@code FindTypes} 는 타입이 쓰인 모든 곳을 표시하고 하위 타입을 따라가지 않는다. 빈 등록 방식이 바뀌는 경우(ex.
 * Boot 4 의 메시지 컨버터 customizer 전환)에는 "이 타입의 빈을 선언한 곳" 만 필요하다.
 */
public class FindBeanMethodsReturning extends Recipe {

	private static final AnnotationMatcher BEAN = new AnnotationMatcher("@org.springframework.context.annotation.Bean");

	@Option(displayName = "Fully qualified type name", description = "이 타입이거나 이 타입을 상속/구현한 반환 타입",
			example = "org.springframework.http.converter.HttpMessageConverter")
	private final String fullyQualifiedTypeName;

	public FindBeanMethodsReturning(String fullyQualifiedTypeName) {
		this.fullyQualifiedTypeName = fullyQualifiedTypeName;
	}

	public String getFullyQualifiedTypeName() {
		return this.fullyQualifiedTypeName;
	}

	@Override
	public String getDisplayName() {
		return "지정한 타입을 반환하는 @Bean 메서드 탐지";
	}

	@Override
	public String getDescription() {
		return "반환 타입이 지정한 타입이거나 그 하위 타입인 @Bean 메서드를 표시한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>("org.springframework.context.annotation.Bean", false),
				new JavaIsoVisitor<ExecutionContext>() {
					@Override
					public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
							ExecutionContext ctx) {
						J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
						if (m.getReturnTypeExpression() != null
								&& TypeUtils.isAssignableTo(FindBeanMethodsReturning.this.fullyQualifiedTypeName,
										m.getReturnTypeExpression().getType())
								&& m.getLeadingAnnotations().stream().anyMatch(BEAN::matches)) {
							return SearchResult.found(m);
						}
						return m;
					}
				});
	}

}
