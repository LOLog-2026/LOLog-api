# lolog-api 배포 이미지(deployment.md 4장). Java 21로 빌드하고 prod 프로필로 띄운다.
# 비밀값은 이미지에 넣지 않고 실행할 때 환경 변수로 받는다(.dockerignore가 .env를 뺀다).

# 1단계: 빌드. 테스트는 Docker(Testcontainers)가 필요해 여기서는 건너뛰고 CI에서 돌린다.
FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /app
# 의존성을 먼저 받아 두면 소스만 바뀌었을 때 이 층을 다시 쓴다.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar app.jar

# 2단계: 실행. JRE만 두고, root가 아닌 사용자로 띄운다.
FROM eclipse-temurin:21.0.12_8-jre-noble
RUN groupadd --system lolog && useradd --system --gid lolog --no-create-home lolog
WORKDIR /app
COPY --from=build /app/app.jar app.jar
USER lolog
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
