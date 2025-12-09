# Remote API sample server

Minimal FastAPI backend that matches the Remote API Tachiyomi extension contract. All images are served from https://picsum.photos so you can test browsing, details, chapters, and page loading without real content.

## Quick start

```bash
cd server/remoteapi_fastapi
uv sync --group dev
uv run uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Using Docker (Python 3.13 + uv, installs from pyproject):

```bash
docker compose -f docker-compose.yml up --build
```

Open http://127.0.0.1:8000/docs to inspect the live schema. The extension default base URL is `http://10.0.2.2:8000` (Android emulator loopback).

## Configuration

Environment variables:

- `REMOTEAPI_API_KEY` — optional token. If set, every request must include the header defined below.
- `REMOTEAPI_API_HEADER` — header name to read the token from. Defaults to `X-Api-Key`.

The `GET /v1/capabilities` response mirrors these values so the Kotlin client knows which header to send.

## Data

The server keeps a small in-memory catalog with three series and generated chapters/pages. Update `FIXTURES` in `main.py` to plug in a real scraper.

## Contract

The API matches the OpenAPI document at `./docs/remote-api-openapi.yaml` and the Kotlin client in `src/all/remoteapi`. Features can be disabled per capability (the server will return HTTP 501) to show graceful degradation on the client.

## Development

Run the quality gates locally:

```bash
uv sync --group dev
uv run ruff check .
uv run mypy .
uv run pytest
```
