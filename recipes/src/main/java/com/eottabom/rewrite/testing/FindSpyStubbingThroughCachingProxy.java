package com.eottabom.rewrite.testing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.SearchResult;

/**
 * 캐시 어노테이션이 있는 빈을 {@code @MockitoSpyBean}/{@code @SpyBean} 으로 감싸고 stubbing 하는 테스트 필드를
 * 표시한다.
 * <p>
 * spy 는 Spring AOP 프록시 안쪽에 들어가서 주입되는 빈은 {@code 캐시 프록시 -> spy -> 원래 객체} 다. 프록시를 통해
 * {@code when(spy.find(..))} 로 stubbing 하면 stubbing 중의 호출 결과(기본값)가 캐시되고, 이후 호출이 spy 에 닿지
 * 않는다. 공식 문서는 {@code AopTestUtils.getUltimateTargetObject(..)} 로 spy 를 꺼내 stubbing 하도록
 * 안내한다 (spring-framework#37121). 이미 AopTestUtils 를 쓰는 파일은 표시하지 않는다.
 */
public class FindSpyStubbingThroughCachingProxy extends ScanningRecipe<Set<String>> {

	private static final List<AnnotationMatcher> CACHE_ANNOTATIONS = List.of(
			new AnnotationMatcher("@org.springframework.cache.annotation.Cacheable"),
			new AnnotationMatcher("@org.springframework.cache.annotation.CachePut"),
			new AnnotationMatcher("@org.springframework.cache.annotation.CacheEvict"),
			new AnnotationMatcher("@org.springframework.cache.annotation.Caching"));

	private static final List<AnnotationMatcher> SPY_ANNOTATIONS = List.of(
			new AnnotationMatcher("@org.springframework.test.context.bean.override.mockito.MockitoSpyBean"),
			new AnnotationMatcher("@org.springframework.boot.test.mock.mockito.SpyBean"));

	private static final List<MethodMatcher> STUBBING = List.of(new MethodMatcher("org.mockito.Mockito when(..)"),
			new MethodMatcher("org.mockito.Mockito do*(..)"), new MethodMatcher("org.mockito.BDDMockito given(..)"),
			new MethodMatcher("org.mockito.BDDMockito will*(..)"));

	private static final MethodMatcher AOP_TEST_UTILS = new MethodMatcher(
			"org.springframework.test.util.AopTestUtils get*TargetObject(..)");

	@Override
	public String getDisplayName() {
		return "캐시 프록시를 거치는 spy stubbing 검토 후보 탐지";
	}

	@Override
	public String getDescription() {
		return "@Cacheable 등이 있는 빈을 @MockitoSpyBean/@SpyBean 으로 stubbing 하는 테스트 필드를 표시한다. "
				+ "프록시를 거친 stubbing 은 기본값을 캐시할 수 있어 AopTestUtils.getUltimateTargetObject 로 spy 를 꺼내 stubbing 한다.";
	}

	@Override
	public Set<String> getInitialValue(ExecutionContext ctx) {
		return new HashSet<>();
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getScanner(Set<String> cachedTypes) {
		return new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
				J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
				JavaType.FullyQualified type = c.getType();
				if (type == null || !hasCacheAnnotation(c)) {
					return c;
				}
				cachedTypes.add(type.getFullyQualifiedName());
				// 필드는 인터페이스 타입으로 선언하는 경우가 많다
				type.getInterfaces().forEach((i) -> cachedTypes.add(i.getFullyQualifiedName()));
				return c;
			}
		};
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor(Set<String> cachedTypes) {
		return new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
				if (cachedTypes.isEmpty() || !stubsWithoutAopTestUtils(cu)) {
					return cu;
				}
				return super.visitCompilationUnit(cu, ctx);
			}

			@Override
			public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
					ExecutionContext ctx) {
				J.VariableDeclarations v = super.visitVariableDeclarations(multiVariable, ctx);
				JavaType.FullyQualified type = TypeUtils.asFullyQualified(v.getType());
				if (type != null && cachedTypes.contains(type.getFullyQualifiedName())
						&& v.getLeadingAnnotations().stream().anyMatch((a) -> matchesAny(SPY_ANNOTATIONS, a))) {
					return SearchResult.found(v);
				}
				return v;
			}
		};
	}

	private static boolean hasCacheAnnotation(J.ClassDeclaration c) {
		if (c.getLeadingAnnotations().stream().anyMatch((a) -> matchesAny(CACHE_ANNOTATIONS, a))) {
			return true;
		}
		return c.getBody()
			.getStatements()
			.stream()
			.filter(J.MethodDeclaration.class::isInstance)
			.map(J.MethodDeclaration.class::cast)
			.anyMatch((m) -> m.getLeadingAnnotations().stream().anyMatch((a) -> matchesAny(CACHE_ANNOTATIONS, a)));
	}

	private static boolean matchesAny(List<AnnotationMatcher> matchers, J.Annotation annotation) {
		return matchers.stream().anyMatch((m) -> m.matches(annotation));
	}

	private static boolean stubsWithoutAopTestUtils(J.CompilationUnit cu) {
		AtomicBoolean stubs = new AtomicBoolean();
		AtomicBoolean unwraps = new AtomicBoolean();
		new JavaIsoVisitor<Integer>() {
			@Override
			public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Integer p) {
				stubs.compareAndSet(false, STUBBING.stream().anyMatch((m) -> m.matches(method)));
				unwraps.compareAndSet(false, AOP_TEST_UTILS.matches(method));
				return super.visitMethodInvocation(method, p);
			}
		}.visit(cu, 0);
		return stubs.get() && !unwraps.get();
	}

}
