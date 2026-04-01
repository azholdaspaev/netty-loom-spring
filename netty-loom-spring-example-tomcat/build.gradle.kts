plugins {
    application
}

dependencies {
    implementation(libs.spring.boot.starter.web)
}

application {
    mainClass.set("io.github.azholdaspaev.nettyloom.example.tomcat.TomcatExampleApplication")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}
