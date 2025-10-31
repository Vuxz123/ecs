plugins {
    id("java-library")
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
    // no external deps; we register the processor via META-INF/services
}

