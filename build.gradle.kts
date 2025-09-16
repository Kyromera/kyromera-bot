import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    kotlin("jvm") version "2.2.0"
    java
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

application {
    mainClass.set("me.diamondforge.kyromera.bot.Main")
}
sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin")
        resources.srcDirs("src/main/resources")
    }
    test {
        kotlin.srcDirs("src/test/kotlin")
        resources.srcDirs("src/test/resources")
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<ShadowJar> {
    mergeServiceFiles()

    archiveFileName.set("kyromera.jar")
}

fun getGitTag(): String? = System.getenv("GIT_TAG")

fun getGitHash(): String? =
    try {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .start()
            .inputStream
            .bufferedReader()
            .readLine()
    } catch (e: Exception) {
        null
    }

fun getTimestamp(): String = SimpleDateFormat("yyyyMMddHHmmss").format(Date())

val tag = getGitTag()
val commitHash = getGitHash() ?: "unknown"
val fallbackVersion = "nightly-$commitHash-${getTimestamp()}"

group = "me.diamondforge"
version = tag ?: fallbackVersion

if (tag != null) {
    println("Using version: $tag")
} else {
    println("Using fallback version: $version")
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

val exposedVersion = "0.61.0"
val ktorVersion = "3.3.0"

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlin:kotlin-serialization")
    implementation("com.zaxxer:HikariCP:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("net.dv8tion:JDA:5.6.1")
    implementation("io.github.freya022:BotCommands:3.0.0-beta.2")
    implementation("org.flywaydb:flyway-core:11.13.0")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("dev.reformator.stacktracedecoroutinator:stacktrace-decoroutinator-jvm:2.5.4")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-crypt:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")

    implementation("io.lettuce:lettuce-core:6.8.0.RELEASE")
    implementation("org.apache.commons:commons-pool2:2.12.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.10.2")

    implementation("com.rabbitmq:amqp-client:5.26.0")

    implementation("com.github.Kyromera:levelcard-lib:bd1b074274")
    implementation("com.github.Kyromera:mee6extract:1.0.1")

    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.3.Final:osx-x86_64")
    runtimeOnly("io.netty:netty-resolver-dns-native-macos:4.2.3.Final:osx-aarch_64")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql:11.13.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.isIncremental = true
    options.release.set(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
