import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.google.protobuf") version "0.9.4"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "dev.briiqn"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.google.protobuf:protobuf-java:4.29.3")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
    implementation("com.zaxxer:HikariCP:5.1.0")
    // Jakarta Persistence (Hibernate ORM) — maps the accounts table instead of raw JDBC
    implementation("org.hibernate.orm:hibernate-core:6.6.4.Final")
    implementation("org.hibernate.orm:hibernate-hikaricp:6.6.4.Final")
    implementation("at.favre.lib:bcrypt:0.10.2")
}


protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("dev.briiqn.verbalize.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
