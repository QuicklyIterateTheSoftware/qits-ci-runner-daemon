# HOW THE RUNNER IS BUILT — qits-ci-daemon's docker/Dockerfile for the native compile, with this
# repository's names, and one stage more: the image.
#
# THE ARTIFACT IS AN IMAGE. A runner is the static `qits-ci-runner` binary running inside a container
# on its host, under docker's restart policy, with the host's docker socket bind-mounted — the
# install script qits-ci renders `docker run`s it, and a runner rolls itself over to a new version by
# starting a successor container from the next tag of the same image. So the last stage, and the
# default target, is `image`: the binary and a docker CLI on a small alpine base, pushed as
# `<registry>/qits/qits-ci-runner:<version>`. The `binary` target still exports the bare file for a
# developer who wants it; nothing publishes it.
#
# The toolchain image is `docker/Dockerfile.musl-builder`, built first and named here by
# BUILDER_IMAGE; its LAST stage must stay the toolchain (qits-bootstrap-cli builds qits-ci-daemon's
# copy by default target), which is why the build stages live in this file and not in that one.
#
# A developer's build is two lines and needs no platform:
#
#     docker build -t qits/graalvmce-musl-builder:jdk-25 -f docker/Dockerfile.musl-builder docker/
#     docker build -t qits-ci-runner:dev -f docker/Dockerfile .
#
# (`--target binary --output type=local,dest=out` instead of `-t` for the file alone.)
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
# BEFORE the `image` stage on purpose: the last stage is what a plain build produces, and that must
# stay the artifact.
FROM scratch AS sbom
COPY --from=build /qits-build/ci-runner/target/sbom.json /sbom.json

# --- binary ---------------------------------------------------------------------------------------
# The bare file, for a developer: `--opt target=binary --output type=local,dest=out` writes
# `out/qits-ci-runner`. Not released — the image below is. `.dockerignore` excludes `out/` so a second
# export against the same context stays a cache hit.
FROM scratch AS binary
COPY --from=build /qits-build/ci-runner/target/qits-ci-runner /qits-ci-runner

# --- image ----------------------------------------------------------------------------------------
# THE ARTIFACT, AND THE DEFAULT TARGET: `<registry>/qits/qits-ci-runner:<version>`.
#
# The base is the official docker CLI image because the runner shells `docker` for every container it
# starts, removes and inspects — its own successor included — against the host's daemon through the
# bind-mounted socket; it needs the CLI and nothing else. Pinned to a full version, not `cli`: a
# runner image is a release, and the CLI in it should not move between two builds of one tag. A bare
# Docker Hub name on purpose, as ci-base's `FROM docker:cli` is — the platform's builder rewrites
# docker.io to its mirror, and a developer's build pulls from Hub directly. The static binary needs no
# libc from the base at all; alpine is simply what the CLI image ships on.
#
# ENTRYPOINT replaces the base's `docker-entrypoint.sh`, and the empty CMD drops its `sh`, which
# would otherwise arrive as the runner's first argument. It runs as root: the socket it is handed is
# root-equivalent whoever holds it, and a user inside the image would only need the socket's group
# id, which differs per host. The state directory is where the install script mounts the runner's
# state volume; creating it here makes a mount-less `docker run` of the image fail on registration
# rather than on a missing directory.
FROM docker:29.8.1-cli AS image
COPY --from=build /qits-build/ci-runner/target/qits-ci-runner /usr/local/bin/qits-ci-runner
RUN mkdir -p /var/lib/qits-ci-runner && chmod 700 /var/lib/qits-ci-runner
ENTRYPOINT ["/usr/local/bin/qits-ci-runner"]
CMD []
