package com.eottabom.rewrite.elasticsearch;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * elasticsearch-java 8.15 (Boot 3.4 BOM) 에서 RangeQuery 가 untyped / date / number / term 중 하나를 고르는 구조로 바뀌었다.
 * 8.14 까지 RangeQuery.Builder 에 있던 field / gte / lte 등은 UntypedRangeQuery.Builder 로 옮겨졌다.
 * <pre>
 * RangeQuery.Builder b = new RangeQuery.Builder().field("price");     UntypedRangeQuery.Builder b = new UntypedRangeQuery.Builder().field("price");
 * q.range(b.gte(JsonData.of(min)).build())                        →    q.range(b.gte(JsonData.of(min)).build()._toRangeQuery())
 * </pre>
 * 원래 타입(8.14 이하)의 RangeQuery.Builder 에 field 메서드가 있을 때만 바꾼다. 이미 8.15 API 로 쓴 코드는 건드리지 않는다.
 * {@code q.range(r -> r.field(...))} 처럼 람다로 쓰는 코드는 바꾸지 않는다 (컴파일 에러로 드러난다).
 */
public class MigrateRangeQueryToUntyped extends Recipe {

    private static final String RANGE_QUERY_BUILDER = "co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery$Builder";
    private static final String UNTYPED_RANGE_QUERY = "co.elastic.clients.elasticsearch._types.query_dsl.UntypedRangeQuery";
    private static final String UNTYPED_RANGE_QUERY_BUILDER = UNTYPED_RANGE_QUERY + "$Builder";

    @Override
    public String getDisplayName() {
        return "elasticsearch-java 8.15 RangeQuery → UntypedRangeQuery";
    }

    @Override
    public String getDescription() {
        return "RangeQuery.Builder 변수를 UntypedRangeQuery.Builder 로 바꾸고, build() 결과를 _toRangeQuery() 로 RangeQuery 로 감싼다.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>("co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery", true), new JavaIsoVisitor<ExecutionContext>() {
            private boolean changed;

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                changed = false;
                J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                if (changed) {
                    doAfterVisit(new ChangeType(RANGE_QUERY_BUILDER, UNTYPED_RANGE_QUERY_BUILDER, true).getVisitor());
                    // 중첩 클래스로 바꾸면 ChangeType 이 바깥 클래스 import 를 추가하지 않는다
                    doAfterVisit(new AddImport<>(UNTYPED_RANGE_QUERY, null, false));
                }
                return c;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                J.NewClass n = super.visitNewClass(newClass, ctx);
                if (isLegacyBuilder(n.getType())) {
                    changed = true;
                }
                return n;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (!"build".equals(m.getSimpleName()) || m.getSelect() == null || !isLegacyBuilder(m.getSelect().getType())) {
                    return m;
                }
                changed = true;
                // build() 는 이제 UntypedRangeQuery 를 돌려주므로 RangeQuery 가 필요한 자리에 맞게 감싼다
                return JavaTemplate.builder("#{any()}._toRangeQuery()")
                        .build()
                        .apply(new Cursor(getCursor().getParent(), m), m.getCoordinates().replace(), m);
            }

            private boolean isLegacyBuilder(JavaType type) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                return fq != null && TypeUtils.isOfClassType(fq, RANGE_QUERY_BUILDER.replace('$', '.'))
                       && fq.getMethods().stream().anyMatch(mt -> "field".equals(mt.getName()));
            }
        });
    }
}
