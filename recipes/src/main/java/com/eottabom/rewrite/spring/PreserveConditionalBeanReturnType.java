package com.eottabom.rewrite.spring;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

/**
 * {@code @Bean} 메서드의 {@code @ConditionalOnBean(annotation = ..)} /
 * {@code @ConditionalOnMissingBean(annotation = ..)} 에 반환 타입을 {@code value} 로 명시한다.
 * <p>
 * Boot 3.4 부터 {@code annotation} 만 지정하면 메서드 반환 타입을 기본 검사 대상으로 쓰지 않는다. 이전에는 "반환 타입의 빈 또는 이
 * 어노테이션이 붙은 빈" 을 봤고, 이제는 "이 어노테이션이 붙은 빈" 만 본다. 컴파일은 그대로 되고 빈 등록 여부만 바뀐다. 릴리즈 노트가 안내하는 대로
 * {@code value} 와 {@code annotation} 을 함께 지정해 이전 동작을 유지한다.
 */
public class PreserveConditionalBeanReturnType extends Recipe {

	private static final AnnotationMatcher BEAN = new AnnotationMatcher("@org.springframework.context.annotation.Bean");

	private static final AnnotationMatcher CONDITIONAL = new AnnotationMatcher(
			"@org.springframework.boot.autoconfigure.condition.ConditionalOn*Bean");

	private static final Set<String> TYPE_ATTRIBUTES = Set.of("value", "type", "name");

	@Override
	public String getDisplayName() {
		return "조건부 빈의 annotation 조건에 반환 타입 명시";
	}

	@Override
	public String getDescription() {
		return "Boot 3.4 부터 @ConditionalOnBean/@ConditionalOnMissingBean 에 annotation 만 주면 @Bean 반환 타입을 기본으로 쓰지 않는다. "
				+ "이전 동작을 유지하도록 value = 반환타입.class 를 추가한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(
				new UsesType<>("org.springframework.boot.autoconfigure.condition.ConditionalOn*Bean", false),
				new JavaIsoVisitor<ExecutionContext>() {
					@Override
					public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method,
							ExecutionContext ctx) {
						J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
						if (m.getLeadingAnnotations().stream().noneMatch(BEAN::matches)) {
							return m;
						}
						NameTree returnType = classLiteralType(m.getReturnTypeExpression());
						if (returnType == null) {
							return m;
						}
						return m.withLeadingAnnotations(ListUtils.map(m.getLeadingAnnotations(),
								(a) -> (CONDITIONAL.matches(a) && annotationOnly(a.getArguments()))
										? addValue(a, returnType) : a));
					}
				});
	}

	/** 인자가 annotation 뿐이고 value/type/name 이 없을 때 */
	private static boolean annotationOnly(List<Expression> arguments) {
		if (arguments == null) {
			return false;
		}
		boolean annotation = false;
		for (Expression arg : arguments) {
			if (!(arg instanceof J.Assignment assignment) || !(assignment.getVariable() instanceof J.Identifier name)) {
				// 이름 없는 인자는 value 다
				return false;
			}
			if (TYPE_ATTRIBUTES.contains(name.getSimpleName())) {
				return false;
			}
			annotation |= "annotation".equals(name.getSimpleName());
		}
		return annotation;
	}

	/** value = 반환타입.class 를 첫 인자로 넣는다 */
	private static J.Annotation addValue(J.Annotation annotation, NameTree returnType) {
		JavaType.FullyQualified type = TypeUtils.asFullyQualified(returnType.getType());
		JavaType classType = new JavaType.Parameterized(null, JavaType.ShallowClass.build("java.lang.Class"),
				List.of(type));
		J.FieldAccess literal = new J.FieldAccess(Tree.randomId(), Space.format(" "), Markers.EMPTY,
				returnType.withPrefix(Space.EMPTY).withId(Tree.randomId()),
				JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, List.of(), "class",
						classType, null)),
				classType);
		J.Assignment first = (J.Assignment) annotation.getArguments().get(0);
		J.Identifier name = ((J.Identifier) first.getVariable()).withId(Tree.randomId()).withSimpleName("value");
		JavaType.FullyQualified annotationType = TypeUtils.asFullyQualified(annotation.getType());
		if (annotationType != null) {
			name = name.withType(annotationType.getMethods()
				.stream()
				.filter((method) -> "value".equals(method.getName()))
				.map(JavaType.Method::getReturnType)
				.findFirst()
				.orElse(name.getType()));
		}
		J.Assignment value = first.withId(Tree.randomId()).withVariable(name).withAssignment(literal);
		List<Expression> arguments = new ArrayList<>();
		arguments.add(value);
		arguments.add(first.withPrefix(Space.format(" ")));
		arguments.addAll(annotation.getArguments().subList(1, annotation.getArguments().size()));
		return annotation.withArguments(arguments);
	}

	/** 클래스 리터럴로 쓸 수 있는 반환 타입. 기본형, 배열, 타입 변수는 null */
	private static NameTree classLiteralType(NameTree returnType) {
		if (returnType instanceof J.ParameterizedType parameterized) {
			return classLiteralType(parameterized.getClazz());
		}
		if ((returnType instanceof J.Identifier || returnType instanceof J.FieldAccess)
				&& returnType.getType() instanceof JavaType.Class) {
			return returnType;
		}
		return null;
	}

}
