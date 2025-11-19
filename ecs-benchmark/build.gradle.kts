plugins {
    id("java-library")
    id("me.champeau.jmh") version "0.7.3" // [cite: 1]
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
    // 1. Phụ thuộc vào CORE để có logic ECS
    implementation(project(":ecs-core"))

    // 2. Phụ thuộc vào PROCESSOR để sinh code Handle cho các bài test
    // (Benchmark cũng cần code generate để chạy nhanh nhất)
    annotationProcessor(project(":ecs-processor"))
}

// Cấu hình JMH Runner
jmh {
    // Số lần chạy (giảm xuống thấp để test nhanh, tăng lên khi đo thật)
    fork.set(1)
    warmupIterations.set(3)
    iterations.set(5)

    // Loại bỏ cảnh báo trùng lặp file (thường gặp khi dùng plugin này)
    duplicateClassesStrategy.set(DuplicatesStrategy.EXCLUDE)

    // --- QUAN TRỌNG NHẤT ---
    // Bắt buộc phải có các cờ này để chạy Panama Foreign Memory trong JMH
    jvmArgs.set(listOf(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector"
    ))
}

// Cấu hình Compiler (để biên dịch code benchmark)
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector"
    ))
}