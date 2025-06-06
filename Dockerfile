FROM eclipse-temurin:17-jdk AS builder


ENV GRADLE_VERSION=8.9

RUN apt-get update && apt-get install -y curl unzip bash git \
    && curl -sSL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o gradle.zip \
    && unzip gradle.zip -d /opt \
    && ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/bin/gradle \
    && rm gradle.zip \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*


WORKDIR /app
COPY . .


RUN gradle shadowJar --no-daemon --stacktrace

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=builder /app/build/libs/Kyromera.jar app.jar
ENV CONFIG_SOURCE=env
ENTRYPOINT ["java", "-jar", "app.jar"]
