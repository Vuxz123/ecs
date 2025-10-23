plugins {
    id("java")
    id("application")
}

group = "com.ethnicthv"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Separate source sets to isolate old and new code
sourceSets {
    // Main source set only includes new component system
    main {
        java {
            exclude("**/World.java")
            exclude("**/ECSDemo.java")
            exclude("**/ImprovedDemo.java")
            exclude("**/MovementSystem.java")
            exclude("**/VectorizedMovementSystem.java")
            exclude("**/PerformanceBenchmark.java")
            exclude("**/ArchetypeDemo.java")
            exclude("**/ArchetypeVsSparseSetBenchmark.java")
        }
    }
}

application {
    mainClass.set("com.ethnicthv.ecs.demo.ComponentManagerDemo")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector"
    ))
}

tasks.withType<Test> {
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runComponentDemo") {
    group = "application"
    description = "Run the ComponentManager demo"
    mainClass.set("com.ethnicthv.ecs.demo.ComponentManagerDemo")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

