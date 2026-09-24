package com.eottabom.rewrite.testing;

import java.util.Comparator;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

/**
 * Mockito 어노테이션(@Mock, @Spy, @InjectMocks, @Captor) 필드가 있는데 @ExtendWith 가 없는 JUnit 5 테스트에
 * {@code @ExtendWith(MockitoExtension.class)} 와
 * {@code @MockitoSettings(strictness = Strictness.LENIENT)} 를 붙인다.
 * <p>
 * Boot 3.x 까지는 Boot 의 MockitoTestExecutionListener 가 Spring 테스트(@DataJpaTest 등) 안의 이런 필드를
 * MockitoAnnotations.openMocks() 로 초기화해 줬다(lenient). Boot 4 에서 리스너가 제거돼 mock 이 null 이 된다.
 * MockitoExtension 만 붙이면 기본값인 strict stubs 때문에 기존에 통과하던 테스트가 UnnecessaryStubbingException
 * 으로 깨지므로, 기존 동작과 같도록 LENIENT 를 함께 지정한다. (이미 @ExtendWith 가 있는 테스트는 건드리지 않는다)
 */
public class AddLenientMockitoExtension extends Recipe {

	private static final AnnotationMatcher EXTEND_WITH = new AnnotationMatcher(
			"@org.junit.jupiter.api.extension.ExtendWith");

	private static final AnnotationMatcher[] MOCKITO_FIELD_ANNOTATIONS = { new AnnotationMatcher("@org.mockito.Mock"),
			new AnnotationMatcher("@org.mockito.Spy"), new AnnotationMatcher("@org.mockito.InjectMocks"),
			new AnnotationMatcher("@org.mockito.Captor") };

	@Override
	public String getDisplayName() {
		return "Mockito 어노테이션 테스트에 lenient MockitoExtension 추가";
	}

	@Override
	public String getDescription() {
		return "@Mock/@InjectMocks 필드가 있고 @ExtendWith 가 없는 JUnit 5 테스트에 "
				+ "@ExtendWith(MockitoExtension.class) 와 @MockitoSettings(strictness = Strictness.LENIENT) 를 추가한다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(Preconditions.and(new UsesType<>("org.mockito.*", false),
				new UsesType<>("org.junit.jupiter.api.*", false)), new JavaIsoVisitor<ExecutionContext>() {
					@Override
					public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
							ExecutionContext ctx) {
						J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
						if (c.getLeadingAnnotations().stream().anyMatch(EXTEND_WITH::matches) || !hasMockitoFields(c)) {
							return c;
						}
						maybeAddImport("org.junit.jupiter.api.extension.ExtendWith");
						maybeAddImport("org.mockito.junit.jupiter.MockitoExtension");
						maybeAddImport("org.mockito.junit.jupiter.MockitoSettings");
						maybeAddImport("org.mockito.quality.Strictness");
						return JavaTemplate.builder(
								"@ExtendWith(MockitoExtension.class)\n@MockitoSettings(strictness = Strictness.LENIENT)")
							.imports("org.junit.jupiter.api.extension.ExtendWith",
									"org.mockito.junit.jupiter.MockitoExtension",
									"org.mockito.junit.jupiter.MockitoSettings", "org.mockito.quality.Strictness")
							.javaParser(JavaParser.fromJavaVersion()
								.dependsOn(
										"package org.junit.jupiter.api.extension; public @interface ExtendWith { Class<?>[] value(); }",
										"package org.mockito.junit.jupiter; public class MockitoExtension {}",
										"package org.mockito.quality; public enum Strictness { LENIENT, WARN, STRICT_STUBS }",
										"package org.mockito.junit.jupiter; public @interface MockitoSettings { org.mockito.quality.Strictness strictness(); }"))
							.build()
							.apply(updateCursor(c), c.getCoordinates()
								.addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
					}

					private boolean hasMockitoFields(J.ClassDeclaration c) {
						for (Statement s : c.getBody().getStatements()) {
							if (s instanceof J.VariableDeclarations) {
								for (J.Annotation a : ((J.VariableDeclarations) s).getLeadingAnnotations()) {
									for (AnnotationMatcher m : MOCKITO_FIELD_ANNOTATIONS) {
										if (m.matches(a)) {
											return true;
										}
									}
								}
							}
						}
						return false;
					}
				});
	}

}
