package com.eottabom.rewrite.querydsl;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.groovy.GroovyIsoVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.util.List;
import java.util.regex.Pattern;

/**
 * querydsl-jpa / querydsl-apt 에 jakarta classifier 를 적용한다.
 * <p>
 * upstream 의 {@code org.openrewrite.gradle.ChangeDependencyClassifier} 는 버전이 있는 문자열 리터럴만 처리해서
 * 실제 프로젝트에서 흔한 아래 형태를 바꾸지 못한다.
 * <pre>
 *   annotationProcessor "com.querydsl:querydsl-apt:${queryDslVersion}:jpa"   -> ...:${queryDslVersion}:jakarta
 *   implementation "com.querydsl:querydsl-jpa:${querydslVersion}"            -> ...:${querydslVersion}:jakarta
 *   implementation "com.querydsl:querydsl-jpa"   (BOM 관리 버전)            -> com.querydsl:querydsl-jpa::jakarta
 * </pre>
 * querydsl-apt 의 다른 classifier(general, hibernate, jdo 등)는 건드리지 않는다.
 */
public class QuerydslJakartaClassifier extends Recipe {

    private static final String JAKARTA = "jakarta";
    private static final Pattern GSTRING_PREFIX = Pattern.compile("^com\\.querydsl:querydsl-(jpa|apt):$");

    @Override
    public String getDisplayName() {
        return "QueryDSL jakarta classifier 적용";
    }

    @Override
    public String getDescription() {
        return "querydsl-jpa / querydsl-apt 의존성에 `jakarta` classifier 를 적용한다. " +
               "GString 버전 변수와 BOM 관리(버전 생략) 선언도 처리한다.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new IsBuildGradle<>(), new GroovyIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                List<Expression> args = m.getArguments();
                if (args.isEmpty()) {
                    return m;
                }

                Expression first = args.get(0);
                if (first instanceof J.Literal && ((J.Literal) first).getValue() instanceof String) {
                    J.Literal literal = (J.Literal) first;
                    String updated = withJakartaClassifier((String) literal.getValue());
                    if (updated != null) {
                        return m.withArguments(ListUtils.mapFirst(args, a -> replaceLiteral(literal, updated)));
                    }
                } else if (first instanceof G.GString) {
                    G.GString updated = withJakartaClassifier((G.GString) first);
                    if (updated != first) {
                        return m.withArguments(ListUtils.mapFirst(args, a -> updated));
                    }
                }
                return m;
            }
        });
    }

    /**
     * "group:artifact[:version[:classifier]]" 리터럴 처리. 변경이 필요 없으면 null.
     */
    static String withJakartaClassifier(String gav) {
        String[] parts = gav.split(":", -1);
        if (parts.length < 2 || !"com.querydsl".equals(parts[0]) || !isTarget(parts[1])) {
            return null;
        }
        String version = parts.length >= 3 ? parts[2] : "";
        if (parts.length >= 4) {
            String classifier = parts[3];
            // 이미 jakarta 이거나, jpa 이외의 classifier(general, hibernate 등)는 의도된 것으로 보고 유지
            if (JAKARTA.equals(classifier) || !(classifier.isEmpty() || "jpa".equals(classifier))) {
                return null;
            }
        }
        return parts[0] + ":" + parts[1] + ":" + version + ":" + JAKARTA;
    }

    private static G.GString withJakartaClassifier(G.GString gString) {
        List<J> strings = gString.getStrings();
        if (strings.size() < 2 || !(strings.get(0) instanceof J.Literal)) {
            return gString;
        }
        Object prefix = ((J.Literal) strings.get(0)).getValue();
        if (!(prefix instanceof String) || !GSTRING_PREFIX.matcher((String) prefix).matches()) {
            return gString;
        }

        J last = strings.get(strings.size() - 1);
        if (last instanceof G.GString.Value) {
            // "com.querydsl:querydsl-jpa:${version}" -> 끝에 ":jakarta" 추가
            return gString.withStrings(ListUtils.concat(strings, newFragment(":" + JAKARTA)));
        }
        if (last instanceof J.Literal && ((J.Literal) last).getValue() instanceof String) {
            String suffix = (String) ((J.Literal) last).getValue();
            if (":jpa".equals(suffix)) {
                return gString.withStrings(ListUtils.mapLast(strings, s -> newFragment(":" + JAKARTA)));
            }
        }
        return gString;
    }

    private static boolean isTarget(String artifactId) {
        return "querydsl-jpa".equals(artifactId) || "querydsl-apt".equals(artifactId);
    }

    private static J.Literal replaceLiteral(J.Literal literal, String value) {
        String source = literal.getValueSource();
        String quote = source != null && !source.isEmpty() ? source.substring(0, 1) : "\"";
        return literal.withValue(value).withValueSource(quote + value + quote);
    }

    private static J.Literal newFragment(String value) {
        return new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, value, value, null, JavaType.Primitive.String);
    }
}
