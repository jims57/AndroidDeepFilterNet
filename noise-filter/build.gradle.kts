import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.dokka.gradle.DokkaTask

val groupId = "io.github.kaleyravideo"
val artifactId = "android-deepfilternet"
val libraryVersion = "0.0.0"

group = groupId
version = libraryVersion

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrains.dokka)
    alias(libs.plugins.jk1.license.report)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.kaleyra.noise_filter"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    dokkaPlugin(libs.jetbrains.dokka)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kaleyra.video.utils)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(group.toString(), artifactId, version.toString())

    pom {
        name = "AndroidDeepFilterNet"
        description = "An Android library providing noise filtering capabilities derived from the DeepFilterNet project."
        inceptionYear = "2025"
        url = "https://github.com/KaleyraVideo/AndroidDeepFilterNet"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "kaleyra-video"
                name = "Kaleyra Video"
                url = "https://github.com/KaleyraVideo/"
            }
        }
        scm {
            url = "https://github.com/KaleyraVideo/"
            connection = "scm:git:git://github.com/KaleyraVideo/AndroidDeepFilterNet.git"
            developerConnection = "scm:git:ssh://git@github.com/KaleyraVideo/AndroidDeepFilterNet.git"
        }
    }
}

licenseReport {
    outputDir = "$rootDir"
    projects = arrayOf(project) + project.subprojects
    configurations = arrayOf("releaseRuntimeClasspath")
    renderers = arrayOf(InventoryHtmlReportRenderer("THIRD-PARTY-LICENSES.html", "Third party libraries"))
}

tasks.register<Exec>("updateLibVersion") {
    val nextVersion: String = project.extra.properties["newVersion"] as? String ?: libraryVersion
    workingDir = file("../scripts")
    commandLine("python3", "./update_lib_version.py", project.name, libraryVersion, nextVersion)
}

tasks.register<Exec>("moduleLicenseReport") {
    workingDir = rootDir
    description = "Executes the generateLicenseReport Gradle task"
    commandLine("./gradlew", "generateLicenseReport")
}

tasks.named("preBuild") {
    dependsOn("moduleLicenseReport")
}

tasks.named("build") {
    dependsOn(tasks.dokkaHtml)
}

tasks.register<Jar>("dokkaHtmlJar") {
    dependsOn(tasks.dokkaHtml)
    from(tasks.dokkaHtml.flatMap { it.outputDirectory })
    archiveClassifier.set("html-docs")
}

tasks.withType<DokkaTask>().configureEach {
    moduleName.set(project.name)
    moduleVersion.set(project.version.toString())
    outputDirectory.set(layout.projectDirectory.dir("doc"))
}