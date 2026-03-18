plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation("junit:junit:4.13.2")
}

// Include pre-built native libraries and generated Kotlin sources
sourceSets {
    main {
        kotlin.srcDir("kotlin")
        resources.srcDir("jniLibs")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "rdp-transport"
            pom {
                name.set("rdp-transport")
                description.set("IronRDP + UniFFI Kotlin bindings for Android RDP client")
                url.set("https://github.com/GlassOnTin/Haven")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                    }
                }
                scm {
                    url.set("https://github.com/GlassOnTin/Haven")
                }
            }
        }
    }
}
