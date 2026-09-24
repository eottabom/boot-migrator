package com.eottabom.rewrite.spring;

import java.util.Arrays;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveAnnotationVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * DataSource 를 "만들기 위한" 설정 빈에서 {@code @DependsOnDatabaseInitialization} 을 제거한다.
 * <p>
 * upstream {@code DatabaseComponentAndBeanInitializationOrdering} (Boot 2.5 단계, 이후 모든
 * 단계에서 체이닝됨)은 "반환 타입에 DataSource 타입 필드/메서드가 있으면" 이 어노테이션을 붙인다. {@code HikariConfig} 는
 * setDataSource() 가 있어서 걸리는데, 실제로는 DataSource 를 만드는 쪽이라 DB 초기화 빈 → DataSource →
 * HikariConfig → DB 초기화 빈 순환 참조가 생긴다. (BeanCurrentlyInCreationException:
 * 'dataSourceScriptDatabaseInitializer')
 */
public class RemoveDependsOnDatabaseInitializationFromDataSourceConfig extends Recipe {

	private static final String DEPENDS_ON = "org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization";

	private static final AnnotationMatcher DEPENDS_ON_MATCHER = new AnnotationMatcher("@" + DEPENDS_ON);

	private static final AnnotationMatcher BEAN_MATCHER = new AnnotationMatcher(
			"@org.springframework.context.annotation.Bean");

	/** DataSource 를 만드는 데 쓰이는 설정 타입 */
	private static final List<String> DATA_SOURCE_CONFIG_TYPES = Arrays.asList("com.zaxxer.hikari.HikariConfig",
			"org.springframework.boot.autoconfigure.jdbc.DataSourceProperties",
			"org.springframework.boot.jdbc.autoconfigure.DataSourceProperties", "javax.sql.DataSource");

	@Override
	public String getDisplayName() {
		return "DataSource 설정 빈의 @DependsOnDatabaseInitialization 제거";
	}

	@Override
	public String getDescription() {
		return "HikariConfig / DataSourceProperties / DataSource 를 반환하는 @Bean 메서드에서 @DependsOnDatabaseInitialization 을 제거해 "
				+ "DB 초기화 빈과의 순환 참조를 막는다.";
	}

	@Override
	public TreeVisitor<?, ExecutionContext> getVisitor() {
		return Preconditions.check(new UsesType<>(DEPENDS_ON, false), new JavaIsoVisitor<ExecutionContext>() {
			@Override
			public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
				J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
				if (m.getLeadingAnnotations().stream().noneMatch(BEAN_MATCHER::matches)
						|| m.getLeadingAnnotations().stream().noneMatch(DEPENDS_ON_MATCHER::matches)
						|| !isDataSourceConfig(
								(m.getReturnTypeExpression() == null) ? null : m.getReturnTypeExpression().getType())) {
					return m;
				}
				m = (J.MethodDeclaration) new RemoveAnnotationVisitor(DEPENDS_ON_MATCHER).visitNonNull(m, ctx,
						getCursor().getParentOrThrow());
				maybeRemoveImport(DEPENDS_ON);
				return m;
			}

			private boolean isDataSourceConfig(JavaType type) {
				return DATA_SOURCE_CONFIG_TYPES.stream().anyMatch((t) -> TypeUtils.isAssignableTo(t, type));
			}
		});
	}

}
