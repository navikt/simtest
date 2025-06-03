FROM gcr.io/distroless/java21
ENV JAVA_OPTS="-Dlogback.configurationFile=logback-remote.xml"
COPY build/libs/app*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
