plugins {
    application // Để chạy demo (có hàm main)
}

group = "io.github.ethnicthv"
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
    // 1. CORE: Để dùng World, SystemManager, ComponentManager
    implementation(project(":ecs-core"))

    // 2. PROCESSOR: Để sinh code (Handle, Injector) lúc compile
    // Đây là dòng quan trọng nhất để fix lỗi "Chicken & Egg"
    annotationProcessor(project(":ecs-processor"))
    testAnnotationProcessor(project(":ecs-processor"))

    // 3. Testing Framework
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite-api")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine")

    // Hỗ trợ annotation javax (nếu cần cho một số IDE cũ)
    implementation("javax.annotation:javax.annotation-api:1.3.2")
}

application {
    // Class chính để chạy khi gõ ./gradlew :ecs-test:run
    // Tớ set mặc định là cái Demo mới nhất của chúng ta
    mainClass.set("com.ethnicthv.ecs.demo.SystemAPIDemo")
}

// Cấu hình Compiler cho Java 25 + Panama
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector"
    ))
}

// Cấu hình Test
tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED", // Bắt buộc cho Panama Foreign Memory
        "--add-modules", "jdk.incubator.vector"
    )
}

// Cấu hình Run (cho application plugin)
tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector"
    )
}

// --- Các Task chạy Demo cũ (Migrate từ file cũ sang) ---
// Tớ giữ lại để cậu tiện chạy các demo cũ nếu muốn

tasks.register<JavaExec>("runSystemDemo") {
    group = "application"
    description = "Run the System API Demo"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ethnicthv.ecs.demo.SystemAPIDemo")
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED", "--add-modules", "jdk.incubator.vector")
}