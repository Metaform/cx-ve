# syntax=docker/dockerfile:1

# Builds the Compliance Tracker lifecycle agent. Build from this directory with:
#   docker build -t compliance-tracker .

# ---- Build ----
# Pinned to $BUILDPLATFORM and cross-compiled via GOOS/GOARCH: in a multi-platform build
# (linux/amd64 + linux/arm64) the compiler runs once on the builder's native arch instead of
# under QEMU emulation. Only the (static, scratch-like) runtime stage is per-platform.
FROM --platform=$BUILDPLATFORM golang:1.25-alpine AS build
WORKDIR /src

ARG TARGETOS
ARG TARGETARCH

COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download

COPY . .

# Cache mounts keep the module and build caches across image builds
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -ldflags="-s -w" -o /out/compliance-tracker ./cmd/server

# ---- Runtime ----
# distroless:nonroot already runs as uid 65532 and carries CA certificates
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /out/compliance-tracker /compliance-tracker

ENTRYPOINT ["/compliance-tracker"]
