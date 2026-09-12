# =============================================================================
#  后端镜像（多阶段构建）
#    stage 1：maven 构建 jar（需要能访问 Maven 中央仓库；离线环境请改为挂载本机 jar）
#    stage 2：JRE 运行（项目实际使用 JDK 17，pom 的 target 也是 17）
# =============================================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline || true
COPY src ./src
# 后端会从 ../frontend/static 复制静态资源，构建上下文是 backend/，这里用参数允许跳过
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=Asia/Shanghai LANG=C.UTF-8
COPY --from=build /src/target/*.jar /app/app.jar
# 上传目录（compose 会挂载宿主机目录）
RUN mkdir -p /data/upload/avatar /data/upload/culture /data/upload/media /data/backups
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
