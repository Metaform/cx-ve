.PHONY: build test test-unit vet clean docker-build

BINARY=compliance-tracker
BUILD_DIR=bin
SERVER_PATH=./cmd/server
DOCKER_IMAGE=ghcr.io/metaform/cx-ve/compliance-tracker
DOCKER_TAG=latest

export CGO_ENABLED=0

build:
	go build -o $(BUILD_DIR)/$(BINARY) $(SERVER_PATH)

# The launcher suite starts a NATS testcontainer and therefore needs a running Docker daemon
test:
	go test -count=1 ./...

# Everything that runs without Docker
test-unit:
	go test -count=1 ./handler/...

vet:
	go vet ./...

clean:
	rm -rf $(BUILD_DIR)

docker-build:
	docker build -t $(DOCKER_IMAGE):$(DOCKER_TAG) .
