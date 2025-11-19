import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    base
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

// Cấu hình chung cho TẤT CẢ các module con (core, processor, test, benchmark)
subprojects {
    // 1. Định danh chung
    group = "io.github.vuxz123"
    version = "0.1.0"

    // 2. Kho thư viện chung (đỡ phải khai báo 4 lần)
    repositories {
        mavenCentral()
    }

    // 3. Cấu hình Java chung (Chỉ áp dụng nếu module đó là Java project)
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }

        // 4. Tự động bật Preview & Panama cho tất cả module
        // Cậu sẽ KHÔNG cần khai báo lại cái này ở module con nữa!
        tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf(
                "--enable-preview",
                "--add-modules", "jdk.incubator.vector"
            ))
        }

        // Cấu hình Test chung
        tasks.withType<Test> {
            useJUnitPlatform()
            jvmArgs(
                "--enable-preview",
                "--enable-native-access=ALL-UNNAMED",
                "--add-modules", "jdk.incubator.vector"
            )
        }
    }
}

// Lọc ra các module cần publish và duyệt qua từng cái
subprojects {
    // Chỉ áp dụng cho 2 module này
    if (name == "ecs-core" || name == "ecs-processor") {

        // Ép Gradle luôn tìm file secring.gpg ở thư mục gốc project (Root Project)
        // thay vì tìm trong thư mục con.
        extra["signing.secretKeyRingFile"] = rootProject.file("secring.gpg").absolutePath.replace("\\", "/")

        // Apply plugin
        apply(plugin = "com.vanniktech.maven.publish")

        // Cấu hình Extension (Dùng extensions.configure thay vì configure<T>)
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            signAllPublications()

            coordinates(
                groupId = "io.github.vuxz123",
                artifactId = project.name,
                version = "0.1.0"
            )

            pom {
                name.set("My ECS Engine")
                description.set("A high-performance Entity Component System for Java using Project Panama.")
                inceptionYear.set("2025")
                url.set("https://github.com/vuxz123/ecs")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("vuxz123")
                        name.set("EthnicTHV")
                        url.set("https://github.com/vuxz123")
                    }
                }

                scm {
                    url.set("https://github.com/vuxz123/ecs")
                    connection.set("scm:git:git://github.com/vuxz123/ecs.git")
                    developerConnection.set("scm:git:ssh://git@github.com/vuxz123/ecs.git")
                }
            }
        }
    }
}