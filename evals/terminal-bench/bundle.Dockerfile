# samizdat and jolt for a Terminal-Bench task container. Linux x86_64 because
# that is what jolt ships and what the task images are.
FROM --platform=linux/amd64 ubuntu:24.04
ARG JOLT_VERSION=v0.8.24
RUN apt-get update -qq && apt-get install -y -qq ca-certificates curl git libsqlite3-0 libssl3 zlib1g unzip zstd >/dev/null
# OpenSSL 3 for task images too old to have it (Debian 11 ships 1.1), built by
# conda-forge against an old glibc. The agent points jolt at it only when the image
# has no libssl.so.3 of its own.
ARG OPENSSL_CONDA=https://conda.anaconda.org/conda-forge/linux-64/openssl-3.5.9-h781a0a9_0.conda
ARG OPENSSL_SHA256=2f46a2753a354bd3d5bcda405623f0b3b51e67ed9f3c2f13aa3bc1fa78bc19bb
RUN cd /tmp && curl -fsSL "$OPENSSL_CONDA" -o openssl.conda \
 && echo "$OPENSSL_SHA256  openssl.conda" | sha256sum -c - \
 && unzip -q openssl.conda 'pkg-*.tar.zst' && mkdir -p /tmp/ossl /opt/samizdat/lib \
 && tar --zstd -xf pkg-*.tar.zst -C /tmp/ossl \
 && cp -L /tmp/ossl/lib/libssl.so.3 /tmp/ossl/lib/libcrypto.so.3 /opt/samizdat/lib/ \
 && rm -rf /tmp/ossl /tmp/openssl.conda /tmp/pkg-*
RUN mkdir -p /opt/samizdat/jolt && curl -fsSL \
      https://github.com/jolt-lang/jolt/releases/download/${JOLT_VERSION}/jolt-${JOLT_VERSION}-x86_64-linux.tar.gz \
    | tar -xz -C /opt/samizdat/jolt --strip-components=1
COPY repo /opt/samizdat/repo
ENV JOLT_GITLIBS_DIR=/opt/samizdat/gitlibs
ENV PATH=/opt/samizdat/jolt/bin:/opt/samizdat/jolt:/usr/local/bin:/usr/bin:/bin
WORKDIR /opt/samizdat/repo
RUN jolt -e '(require (quote samizdat.core)) (println :loaded)'
RUN cp -r /root/.jolt /opt/samizdat/dotjolt
