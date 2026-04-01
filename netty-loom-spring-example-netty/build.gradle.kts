plugins {
    application
}

dependencies {
    implementation(project(":netty-loom-spring-boot-starter"))
}

application {
    mainClass.set("io.github.azholdaspaev.nettyloom.example.netty.NettyExampleApplication")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}
