plugins {
	kotlin("jvm") version "2.1.10"
	kotlin("plugin.spring") version "2.1.10"
	id("org.springframework.boot") version "3.5.16"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.1.10"
}

group = "com.atleti"
version = "0.0.1-SNAPSHOT"
description = "Atleti-news-backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// Boot 3.5.16이 고르는 버전 가운데 CVE가 있는 것을 같은 줄의 패치 버전으로 올린다 (CI Trivy 게이트).
// Boot 4로 올리면 지운다 — .trivyignore 참고
extra["netty.version"] = "4.1.140.Final"
extra["tomcat.version"] = "10.1.61"
extra["jackson-bom.version"] = "2.21.8"
extra["postgresql.version"] = "42.7.14"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("org.jsoup:jsoup:1.24.1")
	// 로그인 토큰(JWT ES256) + JWKS — 다른 서비스가 공개 키로 검증한다
	implementation("com.nimbusds:nimbus-jose-jwt:10.10")
	implementation("org.json:json:20250107")
	implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")
	implementation("org.ahocorasick:ahocorasick:0.6.3")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
