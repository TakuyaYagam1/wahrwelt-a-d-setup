plugins {
    id("org.springframework.boot") version "latest.release"
    id("java")
}

group = "ru.serega6531"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

configurations {
    get("compileOnly").apply {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:latest.release"))
    implementation(platform("tools.jackson:jackson-bom:latest.release"))
    implementation("org.apache.tomcat.embed:tomcat-embed-core:latest.release")
    implementation("org.apache.tomcat.embed:tomcat-embed-el:latest.release")
    implementation("org.apache.tomcat.embed:tomcat-embed-websocket:latest.release")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.session:spring-session-core")
    implementation("org.apache.commons:commons-lang3:latest.release")
    implementation("commons-io:commons-io:latest.release")

    implementation("org.pcap4j:pcap4j-core:latest.release")
    implementation("org.pcap4j:pcap4j-packetfactory-static:latest.release")

    constraints {
        implementation("net.java.dev.jna:jna:latest.release") {
            because("Поддерживаем актуальную стабильную версию JNA для native libpcap.")
        }
    }

    implementation("com.google.guava:guava:latest.release")
    implementation("com.google.re2j:re2j:latest.release")
    implementation("org.java-websocket:Java-WebSocket:latest.release")
    implementation("org.bouncycastle:bcprov-jdk18on:latest.release")
    implementation("org.bouncycastle:bctls-jdk18on:latest.release")
    implementation("org.modelmapper:modelmapper:latest.release")
    implementation("com.athaydes.rawhttp:rawhttp-core:latest.release")

    compileOnly("org.jetbrains:annotations:latest.release")
    compileOnly("org.projectlombok:lombok:latest.release")
    runtimeOnly("org.postgresql:postgresql:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter:latest.release")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:latest.release")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Djdk.attach.allowAttachSelf=true")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 27
}
