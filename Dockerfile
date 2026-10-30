# The server image: the native binary (built by CI, `./gradlew :app:linkReleaseExecutableLinuxX64`, copied to
# ./ostrom-server) on distroless/cc (glibc and libgcc only, no shell, non-root). No JVM.
FROM busybox:1.37 AS layout
RUN mkdir -p /out/var/lib/ostrom

FROM gcr.io/distroless/cc-debian12:nonroot
COPY --from=layout --chown=65532:65532 /out/var/lib/ostrom /var/lib/ostrom
COPY --chmod=0755 ostrom-server /usr/local/bin/ostrom-server
WORKDIR /var/lib/ostrom
ENV OSTROM_SIGNING_KEY_FILE=/var/lib/ostrom/instance-key.pem
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/ostrom-server"]
