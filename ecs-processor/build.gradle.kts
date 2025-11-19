plugins {
    id("java-library")
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
    // 1. Cần ecs-core để hiểu các Annotation (@Component, @Query)
    implementation(project(":ecs-core"))

    // 2. (Khuyên dùng) Thư viện này tự động tạo file META-INF/services/...
    // Giúp cậu không phải tạo file thủ công dễ sai sót.
    // Chỉ cần thêm @AutoService(Processor.class) vào class Processor của cậu.
    compileOnly("com.google.auto.service:auto-service:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Bật preview cho Java 25 (để đồng bộ với core)
    options.compilerArgs.add("--enable-preview")
}