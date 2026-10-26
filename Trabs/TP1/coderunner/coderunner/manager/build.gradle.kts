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
    // 1. Depende do módulo local de contratos!
    implementation(project(":contract"))

    // 2. Bibliotecas gRPC
    implementation("io.grpc:grpc-netty-shaded:${grpcVersion}")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}