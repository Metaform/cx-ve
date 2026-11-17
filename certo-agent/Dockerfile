# syntax=docker/dockerfile:1

# Builds the Certo CFM orchestration agent. Build from this directory with:
#   docker build -t certo-agent .

# ---- Build ----
# Pinned to $BUILDPLATFORM and cross-compiled via GOOS/GOARCH, so a multi-platform build compiles
# natively once per target instead of under QEMU emulation.
FROM --platform=$BUILDPLATFORM golang:1.26-alpine AS build
WORKDIR /src

ARG TARGETOS
ARG TARGETARCH

COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download

COPY . .

RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -ldflags="-s -w" -o /out/certo-agent ./cmd/server

# ---- Runtime ----
# distroless:nonroot already runs as uid 65532 and carries CA certificates
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /out/certo-agent /certo-agent

ENTRYPOINT ["/certo-agent"]
