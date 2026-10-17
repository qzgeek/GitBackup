plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "cn.qzgeek"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    compileOnly(files("/home/xcgeek/.gradle/caches/modules-2/files-2.1/dev.folia/folia-api/26.1.2.build.8-stable/12a1b92bd9892bc6aa2fcee1f49c021a0eb0d8/folia-api-26.1.2.build.8-stable.jar"))
    compileOnly(files("/home/xcgeek/.gradle/caches/modules-2/files-2.1/net.kyori/adventure-api/4.26.1/907ea365968cae9bdd84d19f2c258f65cf5f12a4/adventure-api-4.26.1.jar"))
    compileOnly(files("/home/xcgeek/.gradle/caches/modules-2/files-2.1/net.kyori/adventure-key/4.26.1/6ded614dc07cc6c2da418a8e907ee42325badcda/adventure-key-4.26.1.jar"))
    compileOnly(files("/home/xcgeek/.gradle/caches/modules-2/files-2.1/net.kyori/examination-api/1.3.0/8a2d185275307f1e2ef2adf7152b9a0d1d44c30b/examination-api-1.3.0.jar"))
    compileOnly(files("/home/xcgeek/.gradle/caches/modules-2/files-2.1/net.md-5/bungeecord-chat/1.21-R0.2-deprecated+build.21/a87a9222a1dcfa429b4a06264899f65313a4ed5c/bungeecord-chat-1.21-R0.2-deprecated+build.21.jar"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    implementation("com.cronutils:cron-utils:9.2.1")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
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
