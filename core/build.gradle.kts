plugins { kotlin("multiplatform"); id("com.vanniktech.maven.publish"); signing }
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    js { nodejs() }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

mavenPublishing {
    coordinates(group.toString(), "core", version.toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name = "TMark Kotlin Core"
        description = "Kotlin Multiplatform parser and serializer for TMark documents"
        url = "https://github.com/waus/tmark-kotlin"
        licenses { license { name = "MIT License"; url = "https://opensource.org/license/mit"; distribution = "repo" } }
        developers { developer { id = "waus"; name = "Waus"; url = "https://github.com/waus" } }
        scm {
            url = "https://github.com/waus/tmark-kotlin"
            connection = "scm:git:https://github.com/waus/tmark-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/waus/tmark-kotlin.git"
        }
    }
}

signing {
    if (!providers.gradleProperty("signingInMemoryKey").isPresent) {
        useGpgCmd()
    }
}
