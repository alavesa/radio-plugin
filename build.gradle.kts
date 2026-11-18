plugins {
    java
}

group = "fi.alavesa"
version = "0.2.2"

repositories {
    mavenCentral()
    // Paper's 26.x API is published here as <mc>.build.<n>-<channel>; there is no -R0.1-SNAPSHOT any more.
    maven("https://repo.papermc.io/repository/maven-public/")
    // Simple Voice Chat API (optional voice module)
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    // Pinned to an exact stable build for reproducible builds. Bump the build number to move forward.
    compileOnly("io.papermc.paper:paper-api:26.3.build.253-stable")
    // Soft dependencies: never shaded, only touched from classes that load when the plugin is present.
    compileOnly("net.luckperms:api:5.5")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.0")
    // The server's own netty, compile-only: the same-port pack delivery hooks the Minecraft listening channel.
    compileOnly(fileTree("libs") { include("netty-*.jar") })
}

tasks.withType<JavaCompile>().configureEach {
    // Paper 26.3 requires Java 25. --release 25 lets any JDK >= 25 build it (no toolchain download needed).
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
    // the resource pack sources ride inside the jar; the plugin zips them at startup (PackBuilder)
    from("resourcepack") { into("resourcepack") }
}

// prints the compile classpath (used by tools that javap the API)
tasks.register("printCp") { doLast { println(configurations.compileClasspath.get().asPath) } }
