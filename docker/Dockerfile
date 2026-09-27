# HOW THE BINARY IS BUILT — qits-ci-daemon's docker/Dockerfile, with this repository's names.
#
# This repository ships no image. The artifact is `ci-runner/target/qits-ci-runner`, a fully static
# musl binary that qits-artifacts serves as bytes and the install script qits-ci renders downloads to
# /usr/local/bin on a runner host. So the last stage is a `FROM scratch` holding that one file, and
# what a build "produces" is an `--output type=local` of it.
#
# The toolchain image is `docker/Dockerfile.musl-builder`, built first and named here by
# BUILDER_IMAGE; its LAST stage must stay the toolchain (qits-bootstrap-cli builds qits-ci-daemon's
# copy by default target), which is why the build stages live in this file and not in that one.
#
# A developer's build is two lines and needs no platform:
#
#     docker build -t qits/graalvmce-musl-builder:jdk-25 -f docker/Dockerfile.musl-builder docker/
#     docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
#
ARG BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25

# --- stage `build`: the musl native compile -------------------------------------------------------
# USER root because the base image ends on 1001 and the checkout arrives owned by root.
FROM ${BUILDER_IMAGE} AS build
USER root
WORKDIR /qits-build

# The whole reactor. `-am` builds ci-runner's dependency from source, and it has to: the published
# `eu.wohlben.qits:qits-ci-runner-protocol` is never at the version being built here — the reactor's
# version is the one about to be tagged — so the binary and the protocol it speaks come out of one
# compile of one tree. `.dockerignore` keeps target/ and out/ out of the context.
COPY . .

# Where Maven Central is mirrored, when it is: CI passes the platform mirror's in-network route;
# EMPTY — the default, and every hand build — deactivates `.qits-maven-settings.xml`'s central-proxy
# profile and the resolve goes to Maven Central directly. No credential rides along: platform reads
# are anonymous.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL

# `quarkus.native.container-build=false`: we are already inside the container it would have launched.
#
# The `ldd` assertion rides on the same RUN because this is where the binary is: GraalVM emits a
# static-pie executable, so `file` never says "statically linked", and a glibc-linked runner would not
# start on half the hosts a person might install it on.
#
# `makeBom` rides on the same invocation so the QA build and the release build are byte-identical LLB
# and the release's `target=sbom` export is a cache hit. cyclonedx.skipNotDeployed=false is
# load-bearing (qits-ci-daemon's Dockerfile has the measurement): `ci-runner` is packaging quarkus
# and skips maven-deploy, and the plugin silently skips deploy-skipping modules without it.
RUN ./mvnw -B -ntp -s .qits-maven-settings.xml -pl ci-runner -am package -Dnative -DskipTests \
      -Dquarkus.native.container-build=false \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
 && ldd ci-runner/target/qits-ci-runner 2>&1 | grep -qE "statically linked|not a dynamic executable"

# --- sbom -----------------------------------------------------------------------------------------
# The ci-runner module's CycloneDX document, reached with `--opt target=sbom --output type=local`.
# BEFORE the `binary` stage on purpose: the last stage is what a plain build produces, and that must
# stay the artifact.
FROM scratch AS sbom
COPY --from=build /qits-build/ci-runner/target/sbom.json /sbom.json

# --- binary ---------------------------------------------------------------------------------------
# THE ARTIFACT, AND THE DEFAULT TARGET: `--output type=local,dest=out` writes `out/qits-ci-runner`.
# The export directory is not the checkout's concern — `.dockerignore` excludes `out/` so a second
# export against the same context stays a cache hit.
FROM scratch AS binary
COPY --from=build /qits-build/ci-runner/target/qits-ci-runner /qits-ci-runner
