URL ?= http://localhost:8080
MVN_IMAGE := maven:3.9-eclipse-temurin-21

.PHONY: up down logs test test-local burst build

## Run the service + Postgres exactly as deployed (Dockerfile build)
up:
	docker compose up --build -d
	@echo "waiting for readiness..."; \
	for i in $$(seq 1 60); do curl -fs $(URL)/readyz >/dev/null && echo ready && exit 0; sleep 2; done; \
	echo "service did not become ready"; docker compose logs app | tail -50; exit 1

down:
	docker compose down -v

logs:
	docker compose logs -f app

build:
	docker build -t seat-reservation .

## Integration tests (Testcontainers) with a local JDK 21
test-local:
	./mvnw -B test

## Integration tests without a local JDK: Maven runs in a container and drives the host Docker daemon
test:
	docker run --rm --network host \
	  -v /var/run/docker.sock:/var/run/docker.sock \
	  -v "$(CURDIR)":/src -v seats-m2:/root/.m2 -w /src \
	  -e TESTCONTAINERS_RYUK_DISABLED=true -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
	  $(MVN_IMAGE) mvn -B test

## On-sale stampede against any deployment: make burst URL=https://<your-app>.onrender.com
burst:
	./burst.sh $(URL)
