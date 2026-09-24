package com.eottabom.rewrite.feign;

import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Feign 12+ 는 RetryableException 에 retryAfter 를 Long 으로 받는 생성자가 추가돼서
 * {@code new RetryableException(status, message, method, cause, null, request)} 의 null 이
 * Date / Long 둘 다에 매칭된다. (reference to RetryableException is ambiguous). Spring Cloud
 * 2023 (Boot 3.2) 부터 발생.
 * <p>
 * retryAfter 자리의 null 리터럴을 {@code (Long) null} 로 바꾼다. 의미(재시도 시각 없음)는 같다.
 */
public class DisambiguateRetryableExceptionNull extends Recipe {

	private static final String RETRYABLE_EXCEPTION = "feign.RetryableException";

	@Override
	public String getDisplayName() {
		return "Feign RetryableException 생성자 null 인자 모호성 제거";
	}

	@Override
	public String getDescription() {
		return "new RetryableException(..., null, request) 의 retryAfter null 을 (Long) null 로 바꾼다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(RETRYABLE_EXCEPTION, false), new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
				J.NewClass n = super.visitNewClass(newClass, ctx);
				if (!TypeUtils.isOfClassType(n.getType(), RETRYABLE_EXCEPTION)) {
					return n;
				}
				List<Expression> args = n.getArguments();
				// (int status, String message, HttpMethod method, Throwable cause,
				// Date|Long retryAfter, Request request)
				if (args.size() != 6 || !isNullLiteral(args.get(4))) {
					return n;
				}
				Expression retryAfter = args.get(4);
				return JavaTemplate.builder("(Long) null")
					.build()
					.apply(updateCursor(n), retryAfter.getCoordinates().replace());
			}

			private boolean isNullLiteral(Expression e) {
				return e instanceof J.Literal && ((J.Literal) e).getValue() == null
						&& ((J.Literal) e).getType() == JavaType.Primitive.Null;
			}
		});
	}

}
