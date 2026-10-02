LIVE_URL ?= https://seat-reserve-service-project-production.up.railway.app
URL      ?= http://localhost:8080
ADMIN_KEY ?= dev-admin-key

.PHONY: up down logs build burst burst-live postman postman-live

up:            ## build + start app and MySQL (http://localhost:8080)
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f app

build:         ## jar only (JDK 21)
	./mvnw -B -DskipTests package

burst:         ## make burst [URL=...] [ADMIN_KEY=...]
	ADMIN_KEY=$(ADMIN_KEY) ./burst.sh $(URL) --admin-key $(ADMIN_KEY)

burst-live:    ## make burst-live ADMIN_KEY=...
	ADMIN_KEY=$(ADMIN_KEY) ./burst.sh $(LIVE_URL) --admin-key $(ADMIN_KEY)

postman:       ## Postman suite via newman against URL
	newman run src/postman/seat-booking.postman_collection.json \
	  --env-var baseUrl=$(URL) --env-var adminKey=$(ADMIN_KEY)

postman-live:
	newman run src/postman/seat-booking.postman_collection.json \
	  --env-var baseUrl=$(LIVE_URL) --env-var adminKey=$(ADMIN_KEY)
