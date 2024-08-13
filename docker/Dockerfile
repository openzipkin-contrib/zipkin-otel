#
# Copyright The OpenZipkin Authors
# SPDX-License-Identifier: Apache-2.0
#

# zipkin version should match zipkin.version in /pom.xml
ARG zipkin_version=3.4.0

# java_version is used during the installation process to build or download the module jar.
#
# Use latest version here: https://github.com/orgs/openzipkin/packages/container/package/java
# This is defined in many places because Docker has no "env" script functionality unless you use
# docker-compose: When updating, update everywhere.
ARG java_version=21.0.1_p12

# We copy files from the context into a scratch container first to avoid a problem where docker and
# docker-compose don't share layer hashes https://github.com/docker/compose/issues/883 normally.
# COPY --from= works around the issue.
FROM scratch as scratch

COPY . /code/

# This version is only used during the install process. Try to be consistent as it reduces layers,
# which reduces downloads.
FROM ghcr.io/openzipkin/java:${java_version} as install

WORKDIR /code
# Conditions aren't supported in Dockerfile instructions, so we copy source even if it isn't used.
COPY --from=scratch /code/ .

WORKDIR /install

# When true, build-bin/maven/unjar searches /code for the artifact instead of resolving remotely.
# /code contains what is allowed in .dockerignore. On problem, ensure .dockerignore is correct.
ARG release_from_maven_build=false
ENV RELEASE_FROM_MAVEN_BUILD=$release_from_maven_build
# Version of the artifact to unjar. Ex. "2.4.5" or "2.4.5-SNAPSHOT" "main" to use the pom version.
ARG version=main
ENV VERSION=$version
ENV MAVEN_PROJECT_BASEDIR=/code
RUN /code/build-bin/maven/maven_build_or_unjar io.zipkin.contrib.otel zipkin-module-otel ${VERSION} module

# zipkin version should match zipkin.version in /code/pom.xml
FROM ghcr.io/openzipkin/zipkin:$zipkin_version as zipkin-otel
LABEL org.opencontainers.image.description="Zipkin with OpenTelemetry on OpenJDK and Alpine Linux"
LABEL org.opencontainers.image.source=https://github.com/openzipkin-contrib/zipkin-otel

# Add installation root as a module
ARG module=otel
COPY --from=install --chown=${USER} /install/* /zipkin/${module}

# Readback is currently not supported
ENV QUERY_ENABLED false

# * Active profile typically corresponds $1 in module/src/main/resources/zipkin-server-(.*).yml
ENV MODULE_OPTS="-Dloader.path=${module} -Dspring.profiles.active=${module}"