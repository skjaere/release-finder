plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.kover)
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

kotlin {
    // Java 17 bytecode, so Android apps can use the library too.
    jvmToolchain(17)
    // Everything public is public on purpose: the compiler asks for a visibility on each declaration.
    explicitApi()
}

java {
    withSourcesJar()
}

dependencies {
    api(libs.newznab.kotlin)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // A real engine, for a read-timeout test, and OkHttp's header check, which UserAgentRule must agree with.
    testImplementation(libs.ktor.client.okhttp)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "release-finder"
                description = "Finds, parses and ranks Usenet releases for an episode or movie across Newznab indexers"
                licenses {
                    license {
                        name = "PolyForm Noncommercial License 1.0.0"
                        url = "https://polyformproject.org/licenses/noncommercial/1.0.0"
                    }
                }
            }
        }
    }
}

// The licence goes into the jars in a folder of its own, so it doesn't clash with another library's.
tasks.withType<Jar>().configureEach {
    into("META-INF/${project.name}") {
        from("LICENSE.md")
    }
}
