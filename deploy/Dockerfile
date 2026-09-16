# 后端镜像：多阶段构建
# 阶段1用 Maven 编译，阶段2只带 JRE 和 fat-jar —— 运行镜像不含源码与构建缓存
# 构建上下文必须是项目根目录（compose 里已配好）：
#   docker build -f deploy/Dockerfile .
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 用基础镜像自带的 mvn，不用 ./mvnw，两个理由：
#  · mvnw 的 Unix 可执行位在 Windows 上建库时 git 不会记录，Linux 上 clone 出来是 0644，
#    执行 ./mvnw 直接 exit 126 Permission denied（首次部署踩过）
#  · .mvn/wrapper/*.jar 被 .gitignore 屏蔽，wrapper 每次构建都要现下 apache-maven 分发包
# 先只拷依赖声明层：pom 不变时 Docker 层缓存直接复用，代码改动不触发重新下载依赖
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline || true
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
# JVM 容器感知：JDK17 默认 UseContainerSupport，-XX:MaxRAMPercentage 让堆随容器限额伸缩
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
