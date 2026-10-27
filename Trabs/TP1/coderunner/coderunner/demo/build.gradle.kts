plugins {
    id("java")
    id("application")
}

group = "isel.coderunner"
version = "1.0"

repositories {
    mavenCentral()
}

val grpcVersion = "1.62.2"

dependencies {
    // Contratos partilhados
    implementation(project(":contract"))

    // Dependências gRPC
    implementation("io.grpc:grpc-netty-shaded:${grpcVersion}")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
}

application {
    mainClass.set("coderunner.demo.DemoApp")
}