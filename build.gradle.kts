plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "cn.qzgeek"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    compileOnly(files("/home/xcgeek/heos-test-26/libraries/fun/bm/lophine/lophine-api/26.1.2/lophine-api-26.1.2.jar"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    implementation("com.cronutils:cron-utils:9.2.1")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.jar { enabled = false }

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.eclipse.jgit", "cn.qzgeek.gitbackup.libs.jgit")
    relocate("org.eclipse.jgit", "cn.qzgeek.gitbackup.libs.jgit")
    relocate("com.cronutils", "cn.qzgeek.gitbackup.libs.cronutils")
    minimize()
}

tasks.build { dependsOn(tasks.shadowJar) }
