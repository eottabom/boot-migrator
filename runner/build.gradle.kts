plugins {
    `java-gradle-plugin`
}

// 레시피 jar 와 달리 이 플러그인은 이 저장소의 Gradle JVM 에서만 돈다 (대상 프로젝트 classpath 에 올라가지 않는다)
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

gradlePlugin {
    plugins {
        create("migration") {
            id = "com.eottabom.migration"
            implementationClass = "com.eottabom.migration.MigrationPlugin"
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // playbook/*.yml
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
