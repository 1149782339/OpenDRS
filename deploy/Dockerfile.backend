# Runtime-only image: the jar is produced on the host by `deploy/dev.sh`
# (`mvn -DskipTests package`). Compiling inside the image would need a full JDK toolchain
# in the podman machine, which is capped at 2 GiB, and would turn a 5 second rebuild into
# a 5 minute one. Keeping the build out of the image is deliberate, not an oversight.
FROM docker.m.daocloud.io/library/eclipse-temurin:21-jre

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app
COPY opendrs-service/target/opendrs-service-*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
