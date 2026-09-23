package com.eottabom.rewrite.gradle;

import org.openrewrite.ExecutionContext;
import org.openrewrite.FindSourceFiles;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.groovy.tree.G;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.Statement;

/**
 * settings.gradle(.kts) 의 최상위 {@code dependencies { }} 블록을 지운다.
 * <p>
 * upstream {@code org.openrewrite.java.dependencies.UpgradeDependencyVersion (artifactId: *)} 이 중첩 빌드(자기 settings 를 가진
 * 하위 디렉토리)의 settings 스크립트를 빌드 파일로 보고 {@code dependencies { runtimeOnly(...) }} 를 넣는 경우가 있다.
 * settings 스크립트에는 최상위 dependencies 가 있을 수 없어(Gradle 이 해석하지 못한다) 그 빌드가 깨진다.
 * buildscript / pluginManagement 안의 dependencies 는 건드리지 않는다.
 */
public class RemoveDependenciesFromSettingsScript extends Recipe {

    @Override
    public String getDisplayName() {
        return "settings 스크립트의 최상위 dependencies 블록 제거";
    }

    @Override
    public String getDescription() {
        return "settings.gradle(.kts) 에 잘못 들어간 최상위 dependencies { } 블록을 지운다. upstream 의존성 레시피가 중첩 빌드의 settings 스크립트에 넣는 문제 보정.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(new FindSourceFiles("**/settings.gradle{,.kts}").getVisitor(), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J preVisit(J tree, ExecutionContext ctx) {
                // Groovy 스크립트는 최상위 문장이 컴파일 단위의 statements 에 바로 들어 있다
                if (tree instanceof G.CompilationUnit) {
                    G.CompilationUnit g = (G.CompilationUnit) tree;
                    return g.withStatements(ListUtils.map(g.getStatements(), this::removeIfDependencies));
                }
                return tree;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);
                // Kotlin 스크립트는 최상위 문장이 컴파일 단위 바로 아래 블록에 들어 있다
                if (getCursor().getParentTreeCursor().getValue() instanceof JavaSourceFile) {
                    return b.withStatements(ListUtils.map(b.getStatements(), this::removeIfDependencies));
                }
                return b;
            }

            private Statement removeIfDependencies(Statement s) {
                return s instanceof J.MethodInvocation && "dependencies".equals(((J.MethodInvocation) s).getSimpleName()) ? null : s;
            }
        });
    }
}
