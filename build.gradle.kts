plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.antithesis"
version = "0.0.1-SNAPSHOT"
description = "Example Spring Boot app tested with Hegel property-based testing"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	// Only the crypto module (PasswordEncoder) — the full security starter would
	// auto-secure every endpoint, which this app does not want.
	implementation("org.springframework.security:spring-security-crypto")
	runtimeOnly("com.h2database:h2")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// Hegel: property-based testing (https://hegel.dev). Requires Java 22+;
	// on Java 17-21 use dev.hegel:hegel-jna instead.
	testImplementation("dev.hegel:hegel:0.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jacoco {
	toolVersion = "0.8.14"
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Hegel's engine uses the FFM API to call into its native core.
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
}

// 100% line + branch coverage of the feature domain logic (web adapters,
// config and bootstrap are wiring and excluded from the gate).
tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	violationRules {
		rule {
			element = "CLASS"
			includes = listOf("com.antithesis.springhegel.user.*")
			excludes = listOf("com.antithesis.springhegel.user.web.*")
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "1.0".toBigDecimal()
			}
			limit {
				counter = "BRANCH"
				value = "COVEREDRATIO"
				minimum = "1.0".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
