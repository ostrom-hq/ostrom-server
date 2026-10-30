# The server image: the native binary (built by `ci/build-linux.sh`, which leaves it in dist/ostrom-server)
# on distroless/cc (glibc and libgcc only, no shell, non-root). No JVM.
# The Kotlin/Native runtime also links libcrypt.so.1, which distroless/cc lacks: it is copied from Debian 12.
FROM debian:12-slim AS layout
RUN mkdir -p /out/var/lib/ostrom /out/lib && cp -L /usr/lib/x86_64-linux-gnu/libcrypt.so.1 /out/lib/

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=layout /out/lib/libcrypt.so.1 /usr/lib/x86_64-linux-gnu/libcrypt.so.1
COPY --from=layout --chown=65532:65532 /out/var/lib/ostrom /var/lib/ostrom
COPY --chmod=0755 dist/ostrom-server /usr/local/bin/ostrom-server
WORKDIR /var/lib/ostrom
ENV OSTROM_SIGNING_KEY_FILE=/var/lib/ostrom/instance-key.pem
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/ostrom-server"]
