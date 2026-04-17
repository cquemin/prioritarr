.PHONY: test install dev

install:
	pip install -r requirements.txt

dev:
	pip install -r requirements-dev.txt

test:
	pytest -v

run:
	uvicorn prioritarr.main:app --host 0.0.0.0 --port 8000 --reload

.PHONY: openapi
openapi:
	@python scripts/generate_openapi.py > openapi.json
	@echo "Wrote openapi.json ($$(wc -c < openapi.json) bytes)"
