plugins {
    java
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.1.0"
    jacoco
}

jacoco {
    // Jacoco 버전 (2026년 기준 최신 안정화 버전 권장)
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    // 2. 리포트 생성 전 테스트가 반드시 실행되도록 설정
    dependsOn(tasks.test)

    reports {
        // html 리포트 활성화 (브라우저로 볼 용도)
        html.required.set(true)
        // xml 리포트 활성화 (SonarQube 등 연동용)
        xml.required.set(true)

        // 리포트 저장 위치를 바꾸고 싶다면 (기본값은 build/reports/jacoco)
        // html.outputLocation.set(layout.buildDirectory.dir("jacocoHtml"))
    }

    // 3. 리포트에서 제외할 클래스 설정 (QueryDSL, DTO 등)
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/dto/**",
                    "**/config/**",
                    "**/*Application*",
                    "**/Q*" // QueryDSL용
                )
            }
        })
    )
}

tasks.test {
    // 4. 테스트 완료 후 자동으로 Jacoco 리포트 생성
    finalizedBy(tasks.jacocoTestReport)
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "NBE8-10-final-Team01"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

spotless {
    java {
        target(
            "src/main/java/**/*.java",
            "src/test/java/**/*.java"
        )

        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        formatAnnotations()

        importOrder("java", "javax", "org", "com", "")
    }

    format("yaml") {
        target("**/*.yml", "**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target("*.gradle", "*.gradle.kts", "*.md", ".gitignore")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.springframework.security:spring-security-test")
    implementation("org.springframework.security:spring-security-messaging")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Auth
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.redisson:redisson:3.27.2")

    // Testcontainers
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
