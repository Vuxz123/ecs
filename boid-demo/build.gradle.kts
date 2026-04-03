plugins {
    application
}

group = "io.github.ethnicthv"
version = "1.0-SNAPSHOT"

val lwjglVersion = "3.3.4"
val imguiVersion = "1.88.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ecs-core"))
    annotationProcessor(project(":ecs-processor"))
    testAnnotationProcessor(project(":ecs-processor"))

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-opengl")

    runtimeOnly("org.lwjgl:lwjgl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-windows")

    implementation("io.github.spair:imgui-java-binding:$imguiVersion")
    implementation("io.github.spair:imgui-java-lwjgl3:$imguiVersion")
    runtimeOnly("io.github.spair:imgui-java-natives-windows:$imguiVersion")

    implementation("javax.annotation:javax.annotation-api:1.3.2")
}

application {
    mainClass.set("com.ethnicthv.ecs.boid.DemoMain")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "--enable-preview",
        "--add-modules", "jdk.incubator.vector"
    ))
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "--add-modules", "jdk.incubator.vector"
    )
}
