plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "kg.vitkas.rag"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // SQLite хранилище
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // HTTP клиент для Ollama
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // Ktor сервер
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-server-status-pages:3.0.3")
    implementation("io.ktor:ktor-server-call-logging:3.0.3")
    implementation("io.ktor:ktor-server-auth:3.0.3")
    // ktor-server-rate-limit НЕ используется: его интерцептор выполняется после фазы Authentication
    // (проверено эмпирически) — не троттлит неудачные Basic Auth попытки, дыра для брутфорса.
    // См. IpRateLimiter.kt — ручной лимитер на более ранней фазе, покрывает все запросы.

    // Coroutines + Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Логирование
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("kg.vitkas.rag.MainKt")
}
