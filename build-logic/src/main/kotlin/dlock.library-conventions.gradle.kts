// Non-application Java modules other than lock-api: lock-client, the SDK. Lombok comes with
// dlock.java-conventions (C5 #ct5-catalog, lombok row). No Spring Boot plugin, no fat jar.

plugins {
    id("dlock.java-conventions")
    `java-library`
}
