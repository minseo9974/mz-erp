plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.mzerp"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	// 시나리오·과정 콘텐츠(TOML) 읽기 — ADR-0015
	implementation("tools.jackson.dataformat:jackson-dataformat-toml")
	// 집계 조회는 MyBatis SQL (ADR-0005)
	implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:4.0.0")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// 통합 테스트용 실제 PostgreSQL 16 (Docker 없이 실행) — ADR-0009
	testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.15.0"))
	testImplementation("io.zonky.test:embedded-postgres:2.2.2")
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.1")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 계정과목 시드의 원본은 저장소 루트 data/seed — 빌드할 때 classpath:seed/ 로 복사한다
tasks.processResources {
	from("../data/seed") {
		into("seed")
	}
	// 시나리오 거래와 정답 — 리플레이어와 골든 테스트가 읽는다
	from("../data/scenario") {
		into("scenario")
	}
	// 과정 콘텐츠(미션) — ADR-0015
	from("../data/course") {
		into("course")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// 로컬 개발: ./gradlew bootTestRun → 임베디드 PostgreSQL과 함께 백엔드 실행 (LocalDevApplication)
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootTestRun") {
	mainClass = "com.mzerp.LocalDevApplication"
}

// 실행용 jar 하나만 만든다 (Docker 이미지에서 이름 고정)
tasks.named<Jar>("jar") {
	enabled = false
}
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	archiveFileName = "app.jar"
}
