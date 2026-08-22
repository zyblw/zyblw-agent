# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-jammy AS build

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
  && echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list \
  && curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
    | gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg \
  && apt-get update \
  && apt-get install -y --no-install-recommends sbt \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /src
COPY project/build.properties project/plugins.sbt project/
COPY build.sbt .
COPY modules modules
COPY deploy/docker/package.sh deploy/docker/package.sh
RUN chmod +x deploy/docker/package.sh && ./deploy/docker/package.sh

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system --gid 10001 zyblw \
  && useradd --system --uid 10001 --gid 10001 --home /app --shell /usr/sbin/nologin zyblw \
  && apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build --chown=zyblw:zyblw /src/deploy/docker/dist/ /app/
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health/live || exit 1
ENTRYPOINT ["/app/run.sh"]
CMD ["serve"]
