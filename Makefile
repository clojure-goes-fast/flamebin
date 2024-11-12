.PHONY: docker-image

GIT_SHA := $(shell git rev-parse HEAD)

remote-pull:
	git fetch && git reset --hard origin/develop

compose:
	GIT_SHA=$(GIT_SHA) docker-compose -f deploy/compose.yml up --build -d; \
	GIT_SHA=$(GIT_SHA) docker-compose -f deploy/compose.yml up --build -d --force-recreate alloy

down:
	docker-compose -f deploy/compose.yml down
