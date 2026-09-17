// lock-api only: the contract module, whose compile classpath is the JDK and nothing else
// (C2 #ct2-zero-dep). No Lombok, no Spring Boot plugin, no fat jar.

plugins {
    id("dlock.java-base")
    `java-library`
}
