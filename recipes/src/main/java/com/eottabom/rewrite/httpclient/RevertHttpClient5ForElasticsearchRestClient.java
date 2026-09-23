package com.eottabom.rewrite.httpclient;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.Arrays;
import java.util.List;

/**
 * upstream UpgradeApacheHttpClient_5 (Spring Framework 6 체인, 매 단계 실행)가 Elasticsearch 저수준 RestClient 설정 코드까지
 * HttpClient 5 로 바꾸는 것을 되돌린다. org.elasticsearch.client.RestClient 는 HttpClient 4(httpcore 4) API 를 쓴다.
 * (no suitable method found for builder(org.apache.hc.core5.http.HttpHost))
 * <p>
 * RestClient 를 쓰는 파일에서만:
 * <ul>
 *   <li>HttpClient 5 타입 → HttpClient 4 타입</li>
 *   <li>{@code new AuthScope(null, -1)} → {@code AuthScope.ANY}</li>
 *   <li>{@code new UsernamePasswordCredentials(u, p.toCharArray())} → {@code (u, p)}</li>
 *   <li>{@code new HttpHost(scheme, host, port)} → {@code new HttpHost(host, port, scheme)}</li>
 * </ul>
 */
public class RevertHttpClient5ForElasticsearchRestClient extends Recipe {

    private static final String REST_CLIENT = "org.elasticsearch.client.RestClient";

    /** hc5 → hc4 (upstream 이 바꾼 것의 역방향) */
    private static final List<String[]> TYPES = Arrays.asList(
            new String[]{"org.apache.hc.core5.http.HttpHost", "org.apache.http.HttpHost"},
            new String[]{"org.apache.hc.core5.http.Header", "org.apache.http.Header"},
            new String[]{"org.apache.hc.core5.http.HttpHeaders", "org.apache.http.HttpHeaders"},
            new String[]{"org.apache.hc.core5.http.HttpResponseInterceptor", "org.apache.http.HttpResponseInterceptor"},
            new String[]{"org.apache.hc.core5.http.HttpRequestInterceptor", "org.apache.http.HttpRequestInterceptor"},
            new String[]{"org.apache.hc.core5.http.message.BasicHeader", "org.apache.http.message.BasicHeader"},
            new String[]{"org.apache.hc.client5.http.auth.AuthScope", "org.apache.http.auth.AuthScope"},
            new String[]{"org.apache.hc.client5.http.auth.UsernamePasswordCredentials", "org.apache.http.auth.UsernamePasswordCredentials"},
            new String[]{"org.apache.hc.client5.http.auth.CredentialsStore", "org.apache.http.client.CredentialsProvider"},
            new String[]{"org.apache.hc.client5.http.auth.CredentialsProvider", "org.apache.http.client.CredentialsProvider"},
            new String[]{"org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider", "org.apache.http.impl.client.BasicCredentialsProvider"}
    );

    @Override
    public String getDisplayName() {
        return "Elasticsearch RestClient 코드의 HttpClient 5 전환 되돌리기";
    }

    @Override
    public String getDescription() {
        return "org.elasticsearch.client.RestClient 를 쓰는 파일에서 upstream 이 바꾼 HttpClient 5 타입과 호출을 HttpClient 4 로 되돌린다.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new UsesType<>(REST_CLIENT, false), new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof J.CompilationUnit)) {
                    return tree;
                }
                Tree t = tree;
                for (String[] pair : TYPES) {
                    t = new ChangeType(pair[0], pair[1], true).getVisitor().visitNonNull(t, ctx);
                }
                return new ExpressionFixes().visitNonNull(t, ctx);
            }
        });
    }

    private static class ExpressionFixes extends JavaVisitor<ExecutionContext> {
        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J j = super.visitNewClass(newClass, ctx);
            if (!(j instanceof J.NewClass)) {
                return j;
            }
            J.NewClass n = (J.NewClass) j;
            List<Expression> args = n.getArguments();

            // new AuthScope(null, -1) -> AuthScope.ANY
            if (TypeUtils.isOfClassType(n.getType(), "org.apache.http.auth.AuthScope") && args.size() == 2
                && isNull(args.get(0)) && isMinusOne(args.get(1))) {
                return JavaTemplate.builder("AuthScope.ANY")
                        .imports("org.apache.http.auth.AuthScope")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(
                                "package org.apache.http.auth; public class AuthScope { public static final AuthScope ANY = null; }"))
                        .build()
                        .apply(getCursor(), n.getCoordinates().replace());
            }

            // new UsernamePasswordCredentials(u, p.toCharArray()) -> (u, p)
            if (TypeUtils.isOfClassType(n.getType(), "org.apache.http.auth.UsernamePasswordCredentials") && args.size() == 2
                && args.get(1) instanceof J.MethodInvocation
                && "toCharArray".equals(((J.MethodInvocation) args.get(1)).getSimpleName())
                && ((J.MethodInvocation) args.get(1)).getSelect() != null) {
                Expression password = ((J.MethodInvocation) args.get(1)).getSelect().withPrefix(args.get(1).getPrefix());
                return n.withArguments(ListUtils.map(args, (i, a) -> i == 1 ? password : a));
            }

            // new HttpHost(scheme, host, port) -> new HttpHost(host, port, scheme)
            if (TypeUtils.isOfClassType(n.getType(), "org.apache.http.HttpHost") && args.size() == 3
                && isString(args.get(0)) && isString(args.get(1)) && isInt(args.get(2))) {
                Expression scheme = args.get(0), host = args.get(1), port = args.get(2);
                J.NewClass reordered = n.withArguments(Arrays.asList(
                        host.withPrefix(scheme.getPrefix()), port.withPrefix(host.getPrefix()), scheme.withPrefix(port.getPrefix())));
                // 생성자 타입의 파라미터 순서도 (hostname, port, scheme) 으로 되돌린다. 남겨 두면 4.0 에서 upstream 이 다시
                // HttpClient 5 로 바꿀 때 ReorderMethodArguments 가 (String, int, String) 패턴과 맞지 않아 순서를 안 바꾼다
                JavaType.Method ctor = n.getConstructorType();
                if (ctor != null && ctor.getParameterTypes().size() == 3 && ctor.getParameterNames().size() == 3) {
                    List<JavaType> types = ctor.getParameterTypes();
                    List<String> names = ctor.getParameterNames();
                    reordered = reordered.withConstructorType(ctor
                            .withParameterTypes(Arrays.asList(types.get(1), types.get(2), types.get(0)))
                            .withParameterNames(Arrays.asList(names.get(1), names.get(2), names.get(0))));
                }
                return reordered;
            }
            return n;
        }

        private static boolean isNull(Expression e) {
            return e instanceof J.Literal && ((J.Literal) e).getValue() == null;
        }

        private static boolean isMinusOne(Expression e) {
            if (e instanceof J.Unary && ((J.Unary) e).getOperator() == J.Unary.Type.Negative) {
                Expression x = ((J.Unary) e).getExpression();
                return x instanceof J.Literal && Integer.valueOf(1).equals(((J.Literal) x).getValue());
            }
            return e instanceof J.Literal && Integer.valueOf(-1).equals(((J.Literal) e).getValue());
        }

        private static boolean isString(Expression e) {
            return TypeUtils.isString(e.getType());
        }

        private static boolean isInt(Expression e) {
            return e.getType() == JavaType.Primitive.Int || TypeUtils.isOfClassType(e.getType(), "java.lang.Integer");
        }
    }
}
