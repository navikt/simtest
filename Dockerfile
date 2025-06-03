FROM ghcr.io/navikt/baseimages/temurin:21
ENV JAVA_OPTS="-Dlogback.configurationFile=logback-remote.xml"
COPY build/libs/app*.jar app.jar
