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

application {
    // updated to use the demo package where the entry points were moved
    mainClass.set("com.ethnicthv.ecs.demo.ECSDemo")
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

tasks.register<JavaExec>("runBenchmark") {
    group = "application"
    description = "Run the performance benchmark"
    mainClass.set("com.ethnicthv.ecs.demo.PerformanceBenchmark")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("runComponentDemo") {
    group = "application"
    description = "Run the ComponentManager demo"
    mainClass.set("com.ethnicthv.ecs.demo.ComponentManagerDemo")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runImproved") {
    group = "application"
    description = "Run the improved demo with Query API and true SoA"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ethnicthv.ecs.demo.ImprovedDemo")
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runArchetypeDemo") {
    group = "application"
    description = "Run the Archetype-based ECS demo"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ethnicthv.ecs.demo.ArchetypeDemo")
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runArchetypeBenchmark") {
    group = "application"
    description = "Benchmark Archetype vs SparseSet ECS"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ethnicthv.ecs.demo.ArchetypeVsSparseSetBenchmark")
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runArchetypeQueryDemo") {
    group = "application"
    description = "Run the ArchetypeQuery demo"
    mainClass.set("com.ethnicthv.ecs.demo.ArchetypeQueryDemo")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.register<JavaExec>("runComponentBenchmark") {
    group = "application"
    description = "Run Component Manager + Archetype ECS benchmark"
    mainClass.set("com.ethnicthv.ecs.demo.ComponentManagerBenchmark")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs(
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector",
        "-Xms2G",
        "-Xmx4G"
    )
}