.PHONY: build test vet clean docker-build

BINARY=certo-agent
BUILD_DIR=bin
SERVER_PATH=./cmd/server
DOCKER_IMAGE=ghcr.io/metaform/cx-ve/certo-agent
DOCKER_TAG=latest

export CGO_ENABLED=0

build:
	go build -o $(BUILD_DIR)/$(BINARY) $(SERVER_PATH)

test:
	go test -count=1 ./...

vet:
	go vet ./...

clean:
	rm -rf $(BUILD_DIR)

docker-build:
	docker build -t $(DOCKER_IMAGE):$(DOCKER_TAG) .
