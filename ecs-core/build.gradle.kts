plugins {
    id("java-library") // Đổi thành 'java-library' để tối ưu cho module nền tảng
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
    // Hiện tại Core chỉ dùng JDK chuẩn (Panama), chưa cần thư viện ngoài.
    // Nếu sau này cần logging (vd: slf4j), hãy thêm bằng 'api' hoặc 'implementation'
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Bật preview nếu Java 25 yêu cầu cho các tính năng Panama mới nhất
    options.compilerArgs.add("--enable-preview")
}

tasks.test {
    useJUnitPlatform()
    // Bắt buộc cho Panama (Foreign Memory) khi chạy Test
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}