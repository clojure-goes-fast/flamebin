.PHONY: docker-image

GIT_SHA := $(shell git rev-parse HEAD)

docker-image:
	DOCKER_BUILDKIT=1 docker build -f deploy/Dockerfile -t flamebin:latest .

remote-pull:
	(cd /flamebin/qa/flamebin.dev && git fetch && git reset --hard origin/develop && cd /flamebin/prod/flamebin.dev && git fetch && git reset --hard origin/prod)

compose:
	GIT_SHA=$(GIT_SHA) docker-compose -f deploy/compose.yml up --build -d; \
	GIT_SHA=$(GIT_SHA) docker-compose -f deploy/compose.yml up --build -d --force-recreate alloy

down:
	docker-compose -f deploy/compose.yml down
