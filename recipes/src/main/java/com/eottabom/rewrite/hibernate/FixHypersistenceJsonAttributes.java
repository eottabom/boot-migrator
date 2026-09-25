package com.eottabom.rewrite.hibernate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.ImplementInterface;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Hypersistence Utils 의 JSON 타입({@code @Type(JsonType.class)} 등)에 매핑된 엔티티 속성 객체와 그 하위 객체에
 * {@code implements Serializable} 을 추가한다.
 * <p>
 * hypersistence-utils-hibernate-6x 는 dirty checking 용 복제 시 속성 객체가 Serializable 이어야 한다.
 * 아니면 flush 시점에 NonSerializableObjectException ("The JPA specification requires that the
 * entity attributes are Serializable") 이 난다. Boot 3.x (Hibernate 6) 전환 후 컴파일은 되고
 * 런타임(저장/수정)에서만 터진다.
 * <p>
 * 또한 JSON 컬럼 값 객체(와 그 하위 객체)에 equals 가 없으면 Lombok {@code @EqualsAndHashCode} 를 붙인다. (유령
 * UPDATE, HHH-17294 계열)
 * <ul>
 * <li>Hypersistence: dirty checking(JsonJavaTypeDescriptor.areEqual)이 List/Map 을
 * {@code Objects.equals} 로 비교해서 원소에 equals 가 없으면 값이 같아도 항상 변경으로 보고 트랜잭션마다 UPDATE 를
 * 날린다</li>
 * <li>Hibernate 네이티브 JSON({@code @JdbcTypeCode(SqlTypes.JSON)}): 값 객체의 equals 로 비교한다</li>
 * </ul>
 * Lombok 을 이미 쓰고 부모 클래스가 없는 클래스에만 적용한다.
 */
public class FixHypersistenceJsonAttributes extends ScanningRecipe<FixHypersistenceJsonAttributes.Accumulator> {

	private static final String HIBERNATE_TYPE = "org.hibernate.annotations.Type";

	private static final String SERIALIZABLE = "java.io.Serializable";

	@Override
	public String getDisplayName() {
		return "JSON 컬럼 값 객체 보정 (Serializable, equals)";
	}

	@Override
	public String getDescription() {
		return "@Type(Json*Type.class) 로 매핑된 엔티티 속성의 타입(제네릭 인자 포함)과 그 하위 필드 타입 중 "
				+ "프로젝트 소스에 있는 클래스에 implements Serializable 을 추가하고, equals 가 없으면 @EqualsAndHashCode 를 붙여 "
				+ "유령 UPDATE 를 막는다. @JdbcTypeCode(SqlTypes.JSON) 속성도 대상이다.";
	}

	@Override
	public Accumulator getInitialValue(ExecutionContext ctx) {
		return new Accumulator();
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
		return new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				if (classDecl.getType() != null) {
					Set<String> fieldTypes = acc.fieldTypesByClass
						.computeIfAbsent(classDecl.getType().getFullyQualifiedName(), (k) -> new HashSet<>());
					for (JavaType.Variable member : classDecl.getType().getMembers()) {
						collectTypes(member.getType(), fieldTypes);
					}
				}
				return super.visitClassDeclaration(classDecl, ctx);
			}

			@Override
			public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
					ExecutionContext ctx) {
				if (multiVariable.getLeadingAnnotations()
					.stream()
					.anyMatch(FixHypersistenceJsonAttributes::isJsonTypeAnnotation)) {
					collectTypes(multiVariable.getType(), acc.roots);
				}
				return super.visitVariableDeclarations(multiVariable, ctx);
			}
		};
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
		return new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
				// enum / interface / annotation 은 대상이 아니고, record 는 equals 가 이미 있고
				// @EqualsAndHashCode 를 붙일 수 없다
				if (c.getType() == null || c.getKind() != J.ClassDeclaration.Kind.Type.Class) {
					return c;
				}
				String fqn = c.getType().getFullyQualifiedName();
				// 부모 클래스가 있으면 붙이지 않는다: @EqualsAndHashCode 는 기본으로 부모 필드를 비교하지 않아서
				// 부모 필드만 바뀐 경우 변경을 못 감지해 UPDATE 가 누락될 수 있다
				if (targets(acc).contains(fqn) && c.getExtends() == null && !hasEquals(c) && usesLombok()) {
					maybeAddImport("lombok.EqualsAndHashCode");
					c = JavaTemplate.builder("@EqualsAndHashCode")
						.imports("lombok.EqualsAndHashCode")
						.javaParser(JavaParser.fromJavaVersion()
							.dependsOn("package lombok; public @interface EqualsAndHashCode {}"))
						.build()
						.apply(updateCursor(c),
								c.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
				}
				if (targets(acc).contains(fqn) && !TypeUtils.isAssignableTo(SERIALIZABLE, c.getType())) {
					maybeAddImport(SERIALIZABLE);
					c = (J.ClassDeclaration) new ImplementInterface<ExecutionContext>(c, SERIALIZABLE).visitNonNull(c,
							ctx, getCursor().getParentOrThrow());
				}
				return c;
			}

			private boolean usesLombok() {
				J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
				return cu != null && cu.getImports().stream().anyMatch((i) -> i.getPackageName().equals("lombok"));
			}

			private boolean hasEquals(J.ClassDeclaration c) {
				boolean lombokEquals = c.getLeadingAnnotations()
					.stream()
					.anyMatch((a) -> LOMBOK_EQUALS.stream()
						.anyMatch(
								(n) -> TypeUtils.isOfClassType(a.getType(), n) || n.endsWith("." + a.getSimpleName())));
				return lombokEquals || c.getBody()
					.getStatements()
					.stream()
					.anyMatch((st) -> st instanceof J.MethodDeclaration
							&& "equals".equals(((J.MethodDeclaration) st).getSimpleName()));
			}
		};
	}

	private static final List<String> LOMBOK_EQUALS = Arrays.asList("lombok.EqualsAndHashCode", "lombok.Data",
			"lombok.Value");

	/** 루트 타입에서 시작해 프로젝트 소스 클래스의 필드를 따라가며 대상 전체를 구한다 */
	private static Set<String> targets(Accumulator acc) {
		if (acc.targets == null) {
			acc.targets = reachable(acc, acc.roots);
		}
		return acc.targets;
	}

	private static Set<String> reachable(Accumulator acc, Set<String> roots) {
		Set<String> result = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>(roots);
		while (!queue.isEmpty()) {
			String t = queue.pop();
			if (acc.fieldTypesByClass.containsKey(t) && result.add(t)) {
				queue.addAll(acc.fieldTypesByClass.get(t));
			}
		}
		return result;
	}

	private static boolean isJsonTypeAnnotation(J.Annotation annotation) {
		if (annotation.getArguments() == null) {
			return false;
		}
		// Hibernate 네이티브 JSON: @JdbcTypeCode(SqlTypes.JSON)
		if (TypeUtils.isOfClassType(annotation.getType(), "org.hibernate.annotations.JdbcTypeCode")) {
			return annotation.getArguments().stream().anyMatch((arg) -> {
				J v = (arg instanceof J.Assignment) ? ((J.Assignment) arg).getAssignment() : arg;
				return v instanceof J.FieldAccess && "JSON".equals(((J.FieldAccess) v).getSimpleName());
			});
		}
		if (!TypeUtils.isOfClassType(annotation.getType(), HIBERNATE_TYPE)) {
			return false;
		}
		for (Expression arg : annotation.getArguments()) {
			// @Type(JsonType.class) / @Type(value = JsonStringType.class)
			J target = (arg instanceof J.Assignment) ? ((J.Assignment) arg).getAssignment() : arg;
			if (target instanceof J.FieldAccess
					&& ((J.FieldAccess) target).getTarget().getType() instanceof JavaType.FullyQualified) {
				String fqn = ((JavaType.FullyQualified) ((J.FieldAccess) target).getTarget().getType())
					.getFullyQualifiedName();
				if ((fqn.startsWith("io.hypersistence.") || fqn.startsWith("com.vladmihalcea."))
						&& fqn.contains("Json")) {
					return true;
				}
			}
		}
		return false;
	}

	private static void collectTypes(JavaType type, Set<String> into) {
		if (type instanceof JavaType.Parameterized) {
			for (JavaType p : ((JavaType.Parameterized) type).getTypeParameters()) {
				collectTypes(p, into);
			}
			collectTypes(((JavaType.Parameterized) type).getType(), into);
		}
		else if (type instanceof JavaType.Array) {
			collectTypes(((JavaType.Array) type).getElemType(), into);
		}
		else if (type instanceof JavaType.FullyQualified) {
			into.add(((JavaType.FullyQualified) type).getFullyQualifiedName());
		}
	}

	public static class Accumulator {

		/** 프로젝트 소스에 선언된 클래스 -> 그 클래스의 필드 타입들 */
		final Map<String, Set<String>> fieldTypesByClass = new HashMap<>();

		/** JSON 속성으로 직접 매핑된 타입들 */
		final Set<String> roots = new HashSet<>();

		Set<String> targets;

	}

}
