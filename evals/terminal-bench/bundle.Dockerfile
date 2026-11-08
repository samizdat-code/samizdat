# samizdat and jolt for a Terminal-Bench task container. Linux x86_64 because
# that is what jolt ships and what the task images are.
FROM --platform=linux/amd64 ubuntu:24.04
ARG JOLT_VERSION=v0.8.24
RUN apt-get update -qq && apt-get install -y -qq ca-certificates curl git libsqlite3-0 libssl3 zlib1g >/dev/null
RUN mkdir -p /opt/samizdat/jolt && curl -fsSL \
      https://github.com/jolt-lang/jolt/releases/download/${JOLT_VERSION}/jolt-${JOLT_VERSION}-x86_64-linux.tar.gz \
    | tar -xz -C /opt/samizdat/jolt --strip-components=1
COPY repo /opt/samizdat/repo
ENV JOLT_GITLIBS_DIR=/opt/samizdat/gitlibs
ENV PATH=/opt/samizdat/jolt/bin:/opt/samizdat/jolt:/usr/local/bin:/usr/bin:/bin
WORKDIR /opt/samizdat/repo
RUN jolt -e '(require (quote samizdat.core)) (println :loaded)'
RUN cp -r /root/.jolt /opt/samizdat/dotjolt
