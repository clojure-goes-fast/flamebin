.PHONY: docker-image

docker-image:
	DOCKER_BUILDKIT=1 docker build -f deploy/Dockerfile -t flamebin:latest .

remote-pull:
	(cd /flamebin/qa/flamebin.dev && git fetch && git reset --hard origin/develop && cd /flamebin/prod/flamebin.dev && git fetch && git reset --hard origin/prod)

compose:
	docker-compose -f deploy/compose.yml up --build -d; \
	docker-compose -f deploy/compose.yml up --build -d --force-recreate alloy
