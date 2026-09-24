package com.eottabom.rewrite.jackson;

import java.util.ArrayList;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * {@code @Bean ObjectMapper} 메서드가 {@code JsonMapper} 를 돌려주면 반환 타입을 {@code JsonMapper} 로
 * 바꾼다 (Jackson 3).
 * <p>
 * Boot 4 는 {@code JsonMapper} 빈을 자동 설정하고, {@code JsonMapper} 타입 빈이 있을 때만 물러난다.
 * {@code @Bean
 * ObjectMapper} 는 자동 설정 매퍼를 대체하지 못하고 두 빈이 함께 남는다. 자동 설정 쪽이 {@code @Primary} 라서 직접 만든 매퍼의
 * 모듈과 설정이 MVC 와 메시지 컨버터에 적용되지 않는다 (spring-boot#50870). 컴파일은 그대로 된다.
 * <p>
 * 모든 return 이 {@code JsonMapper} 인 메서드만 바꾼다. {@code new ObjectMapper()} 나 XML/YAML 매퍼를
 * 돌려주는 메서드는 사람이 판단한다.
 */
public class NarrowJsonMapperBeanReturnType extends Recipe {

	private static final String OBJECT_MAPPER = "tools.jackson.databind.ObjectMapper";

	private static final String JSON_MAPPER = "tools.jackson.databind.json.JsonMapper";

	private static final AnnotationMatcher BEAN = new AnnotationMatcher("@org.springframework.context.annotation.Bean");

	@Override
	public String getDisplayName() {
		return "@Bean ObjectMapper 의 반환 타입을 JsonMapper 로";
	}

	@Override
	public String getDescription() {
		return "Boot 4 는 JsonMapper 타입 빈이 있을 때만 자동 설정 매퍼가 물러난다. JsonMapper 를 돌려주는 @Bean ObjectMapper 메서드의 반환 타입을 JsonMapper 로 바꾼다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(OBJECT_MAPPER, false), new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
				J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
				if (m.getReturnTypeExpression() == null || m.getBody() == null
						|| !TypeUtils.isOfClassType(m.getReturnTypeExpression().getType(), OBJECT_MAPPER)
						|| m.getLeadingAnnotations().stream().noneMatch(BEAN::matches)) {
					return m;
				}
				JavaType.FullyQualified jsonMapper = returnsOnlyJsonMapper(m.getBody());
				if (jsonMapper == null) {
					return m;
				}
				maybeAddImport(JSON_MAPPER);
				maybeRemoveImport(OBJECT_MAPPER);
				J.Identifier returnType = new J.Identifier(Tree.randomId(), m.getReturnTypeExpression().getPrefix(),
						Markers.EMPTY, List.of(), jsonMapper.getClassName(), jsonMapper, null);
				m = m.withReturnTypeExpression(returnType);
				return (m.getMethodType() != null) ? m.withMethodType(m.getMethodType().withReturnType(jsonMapper)) : m;
			}
		});
	}

	/** 메서드 본문(람다와 내부 클래스 제외)의 return 이 모두 JsonMapper 이면 그 타입 */
	private static JavaType.FullyQualified returnsOnlyJsonMapper(J.Block body) {
		List<J.Return> returns = new ArrayList<>();
		new JavaIsoVisitor<List<J.Return>>() {
			@Override
			public J.Return visitReturn(J.Return ret, List<J.Return> found) {
				found.add(ret);
				return ret;
			}

			@Override
			public J.Lambda visitLambda(J.Lambda lambda, List<J.Return> found) {
				return lambda;
			}

			@Override
			public J.NewClass visitNewClass(J.NewClass newClass, List<J.Return> found) {
				return newClass;
			}

			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, List<J.Return> found) {
				return classDecl;
			}
		}.visit(body, returns);
		JavaType.FullyQualified jsonMapper = null;
		for (J.Return ret : returns) {
			JavaType.FullyQualified type = (ret.getExpression() != null)
					? TypeUtils.asFullyQualified(ret.getExpression().getType()) : null;
			if (type == null || !TypeUtils.isOfClassType(type, JSON_MAPPER)) {
				return null;
			}
			jsonMapper = type;
		}
		return jsonMapper;
	}

}
