plugins {
	kotlin("jvm")
}

group = "vip.qoriginal"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	implementation(kotlin("stdlib"))
	implementation("com.google.devtools.ksp:symbol-processing-api:2.2.0-2.0.2")
}