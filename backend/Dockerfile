# ───────── 阶段一:Maven 构建(jar)─────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 阿里云 Maven 镜像(国内网络下稳)
COPY settings-docker.xml /root/.m2/settings.xml
# 先单独拷 pom 预热依赖缓存(源码不变时可复用层)
COPY pom.xml .
RUN mvn -q dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -q clean package -DskipTests

# ───────── 阶段二:运行时(仅 JRE + jar)─────────
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
# 容器内用 docker profile(中间件走服务名);API Key 由 compose 注入 DASHSCOPE_API_KEY
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java","-jar","app.jar"]
