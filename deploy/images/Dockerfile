# The repository's only image definition (T-005): one multi-stage build for every deployable service,
# chosen by ARG MODULE. deploy/compose (--profile apps), the container workflow (T-007) and the Artifact
# Registry push (T-054) all build this file; there is no per-module Dockerfile.
#
#   docker build --build-arg MODULE=<module> -f deploy/images/Dockerfile .     # from the repository root
#
# The base tags repeat temurin-image from gradle/libs.versions.toml (C5 #ct5-catalog); move them together.
# The build context is filtered by Dockerfile.dockerignore, next to this file.

FROM eclipse-temurin:26.0.2_10-jdk AS build
ARG MODULE
WORKDIR /workspace
# The whole filtered tree rather than a list: Gradle 9 configures every project that settings.gradle.kts
# includes, so every module directory must exist, and no module may be named here.
COPY . .
# sharing=locked: compose builds the services in parallel, and Gradle cannot hand its cache lock across
# containers, so concurrent builds on one shared cache time out. Locked makes them take turns.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --console=plain ":${MODULE:?pass --build-arg MODULE=<module>}:bootJar"
# bootJar is skipped while a service has no Java sources (dlock.spring-conventions), so a module without
# code stops here with that reason, not further down with a missing-file error.
RUN jar="$(find "${MODULE}/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' 2>/dev/null | head -n 1)"; \
    if [ -z "${jar}" ]; then \
      echo "no bootJar for ${MODULE}: the service has no code yet; its M1/M2 task adds the application class" >&2; \
      exit 1; \
    fi; \
    cp "${jar}" application.jar; \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:26.0.2_10-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /application
# Dependencies first, so a code-only change rebuilds only the last layer.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
# Exec form, no shell wrapper: java is PID 1 and receives SIGTERM itself.
ENTRYPOINT ["java", "-jar", "application.jar"]
