plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "release-finder"

// Builds against a sibling checkout of newznab-kotlin when there is one.
if (file("../newznab-kotlin").exists()) {
    includeBuild("../newznab-kotlin") {
        dependencySubstitution {
            substitute(module("com.github.skjaere:newznab-kotlin")).using(project(":"))
        }
    }
}
