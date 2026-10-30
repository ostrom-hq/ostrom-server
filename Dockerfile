# The server image: the native binary (built by `ci/build-linux.sh`, which leaves it in dist/ostrom-server; the image
# workflow puts the linuxX64 or linuxArm64 one there) on distroless/cc (glibc and libgcc only, no shell, non-root).
# No JVM. The Kotlin/Native runtime also links libcrypt.so.1, and Ktor's Curl client (billing's Stripe calls; libcurl,
# OpenSSL and nghttp2 are linked statically) links libz.so.1; distroless/cc lacks both: they are copied from Debian 12
# of the same architecture (x86_64-linux-gnu or aarch64-linux-gnu).
FROM debian:12-slim AS layout
RUN set -eu; triplet="$(uname -m)-linux-gnu"; \
    mkdir -p /out/var/lib/ostrom "/out/usr/lib/$triplet"; \
    cp -L "/usr/lib/$triplet/libcrypt.so.1" "/usr/lib/$triplet/libz.so.1" "/out/usr/lib/$triplet/"

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=layout /out/usr/ /usr/
COPY --from=layout --chown=65532:65532 /out/var/lib/ostrom /var/lib/ostrom
COPY --chmod=0755 dist/ostrom-server /usr/local/bin/ostrom-server
WORKDIR /var/lib/ostrom
ENV OSTROM_SIGNING_KEY_FILE=/var/lib/ostrom/instance-key.pem
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/ostrom-server"]
