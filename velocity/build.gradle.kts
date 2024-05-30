dependencies {
    implementation(projects.shared)
    implementation("org.bstats:bstats-velocity:3.0.1")
    compileOnly("com.github.limework.redisbungee:RedisBungee-Velocity:0.11.0")
    compileOnly("com.velocitypowered:velocity-api:3.2.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.2.0-SNAPSHOT")
    compileOnly("com.github.LeonMangler:PremiumVanishAPI:2.9.0-4")
    implementation("redis.clients:jedis:5.0.0-beta2")
}

tasks.compileJava {
    options.release.set(11)
}