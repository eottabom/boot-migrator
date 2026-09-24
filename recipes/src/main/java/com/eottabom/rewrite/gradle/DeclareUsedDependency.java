package com.eottabom.rewrite.gradle;

import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.gradle.AddDependencyVisitor;
import org.openrewrite.gradle.IsBuildGradle;
import org.openrewrite.gradle.marker.GradleDependencyConfiguration;
import org.openrewrite.gradle.marker.GradleProject;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.marker.JavaProject;
import org.openrewrite.java.marker.JavaSourceSet;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaSourceFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 코드에서 import 하는데 build.gradle 에 직접 선언되지 않은(transitive 로만 들어오던) 의존성을 선언한다.
 * <p>
 * Boot 2.7 시절엔 spring-cloud 스타터 등을 타고 transitive 로 들어오던 라이브러리(commons-lang3 등)가
 * 버전을 올리며 의존성 그래프가 바뀌면 사라져 컴파일이 깨진다.
 * <p>
 * upstream {@code org.openrewrite.gradle.AddDependency} 로는 해결되지 않는다.
 * <ul>
 *   <li>transitive 로 이미 있으면 무조건 건너뛴다 (업그레이드 전 그래프 기준이라 항상 "있음")</li>
 *   <li>{@code onlyIfUsing} 은 타입 해석에 의존해서, 의존성이 사라진 뒤에는 매칭되지 않는다</li>
 * </ul>
 * 그래서 import 문을 텍스트로 보고, 직접 선언 여부만 확인한다.
 */
public class DeclareUsedDependency extends ScanningRecipe<DeclareUsedDependency.Accumulator> {

    @Option(displayName = "Package",
            description = "이 패키지(하위 포함)를 import 하면 대상으로 본다. 쉼표로 여러 개 지정할 수 있다. " +
                          "같은 실행 안에서 upstream 레시피가 패키지를 바꾸는 경우(ex. commons-lang -> lang3) 바뀌기 전 패키지도 함께 적는다. " +
                          "(스캔은 편집 전 원본 소스 기준으로 한 번만 돈다)",
            example = "org.apache.commons.lang3, org.apache.commons.lang")
    private final String packageName;

    @Option(displayName = "Group", example = "org.apache.commons")
    private final String groupId;

    @Option(displayName = "Artifact", example = "commons-lang3")
    private final String artifactId;

    @Option(displayName = "Version",
            description = "BOM 이 관리하면 생략한다. semver selector 사용 가능 (ex. 2.x)",
            example = "2.x", required = false)
    private final String version;

    @Option(displayName = "Version pattern",
            description = "버전 뒤에 붙는 접미사 패턴 (ex. guava 의 -jre)",
            example = "-jre", required = false)
    private final String versionPattern;

    public DeclareUsedDependency(String packageName, String groupId, String artifactId, String version, String versionPattern) {
        this.packageName = packageName;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.versionPattern = versionPattern;
    }

    public String getVersionPattern() {
        return versionPattern;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public String getDisplayName() {
        return "사용 중인데 선언되지 않은 의존성 선언";
    }

    @Override
    public String getDescription() {
        return "import 하는 패키지의 의존성이 build.gradle 에 직접 선언돼 있지 않으면 implementation(테스트 전용이면 testImplementation)으로 추가한다.";
    }

    public record Accumulator(Map<JavaProject, Set<String>> sourceSetsUsing) {
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator(new HashMap<>());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        List<String> prefixes = new java.util.ArrayList<>();
        for (String p : packageName.split(",")) {
            if (!p.trim().isEmpty()) {
                prefixes.add(p.trim() + ".");
            }
        }
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof J.CompilationUnit)) {
                    return tree;
                }
                J.CompilationUnit cu = (J.CompilationUnit) tree;
                JavaProject project = cu.getMarkers().findFirst(JavaProject.class).orElse(null);
                JavaSourceSet sourceSet = cu.getMarkers().findFirst(JavaSourceSet.class).orElse(null);
                if (project == null || sourceSet == null) {
                    return tree;
                }
                for (J.Import imp : cu.getImports()) {
                    // "org.apache.commons.lang" 이 "org.apache.commons.lang3" 에 매칭되지 않도록 '.' 까지 비교
                    String pkg = imp.getPackageName() + ".";
                    if (prefixes.stream().anyMatch(pkg::startsWith)) {
                        acc.sourceSetsUsing().computeIfAbsent(project, p -> new HashSet<>()).add(sourceSet.getName());
                        break;
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return Preconditions.check(new IsBuildGradle<>(), new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof JavaSourceFile)) {
                    return (J) tree;
                }
                SourceFile s = (SourceFile) tree;
                JavaProject project = s.getMarkers().findFirst(JavaProject.class).orElse(null);
                GradleProject gp = s.getMarkers().findFirst(GradleProject.class).orElse(null);
                Set<String> using = project == null ? null : acc.sourceSetsUsing().get(project);
                if (gp == null || using == null) {
                    return (J) tree;
                }

                // 소스셋마다 자기 configuration 에 넣는다 (main -> implementation, test -> testImplementation,
                // testFixtures -> testFixturesImplementation ...). main 에 넣으면 test 는 따라오지만 testFixtures 는 아니다.
                J result = (J) tree;
                for (String sourceSet : using) {
                    String configuration = "main".equals(sourceSet) ? "implementation" : sourceSet + "Implementation";
                    if (gp.getConfiguration(configuration) == null || isDeclared(gp, coveringConfigurations(sourceSet, using))) {
                        continue;
                    }
                    result = new AddDependencyVisitor(groupId, artifactId, version, versionPattern, configuration,
                            null, null, null, DeclareUsedDependency::isTopLevel, null).visit(result, ctx);
                }
                return result;
            }
        });
    }

    /** 이 소스셋에서 이미 보이는 선언으로 인정하는 configuration 들 */
    private static List<String> coveringConfigurations(String sourceSet, Set<String> using) {
        List<String> c = new java.util.ArrayList<>(Arrays.asList("api", "compileOnlyApi"));
        if ("main".equals(sourceSet)) {
            c.addAll(Arrays.asList("implementation", "compileOnly"));
        } else {
            c.addAll(Arrays.asList(sourceSet + "Implementation", sourceSet + "Api", sourceSet + "CompileOnly"));
            // test 는 main 의 implementation 을 상속한다. main 에 추가될 예정이면 test 에는 따로 넣지 않는다
            if ("test".equals(sourceSet)) {
                c.addAll(Arrays.asList("implementation", "compileOnly"));
                if (using.contains("main")) {
                    return null;
                }
            }
        }
        return c;
    }

    private boolean isDeclared(GradleProject gp, List<String> configurations) {
        if (configurations == null) {
            return true;
        }
        for (String name : configurations) {
            GradleDependencyConfiguration c = gp.getConfiguration(name);
            if (c != null && c.findRequestedDependency(groupId, artifactId) != null) {
                return true;
            }
        }
        return false;
    }

    /** subprojects { dependencies { } } 가 아니라 이 파일의 최상위 dependencies 블록에 넣는다 */
    private static boolean isTopLevel(Cursor cursor) {
        if (cursor.getValue() instanceof J.Block) {
            return cursor.getParentOrThrow().getValue() instanceof JavaSourceFile;
        }
        return cursor.getParentOrThrow().firstEnclosing(J.MethodInvocation.class) == null;
    }
}
