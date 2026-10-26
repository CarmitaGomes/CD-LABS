plugins {
    id("java")
}

group = "isel.coderunner"
version = "1.0"

repositories {
    mavenCentral()
}

val grpcVersion = "1.62.2"

dependencies {
    // 1. Depende do módulo local de contratos
    implementation(project(":contract"))

    // 2. Bibliotecas gRPC
    implementation("io.grpc:grpc-netty-shaded:${grpcVersion}")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // 3. Redis
    implementation("redis.clients:jedis:5.1.0")
    // 4. Docker-Java (usado no Anexo 3)
    implementation("com.github.docker-java:docker-java-core:3.4.0")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.4.0")

    // Slf4j para logs da biblioteca docker-java
    implementation("org.slf4j:slf4j-simple:2.0.9")
}