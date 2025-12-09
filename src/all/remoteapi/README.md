# Remote API extension

A generic Keiyoushi/Tachiyomi extension that delegates all actions to a user-provided HTTP API. Point it at any backend that follows `docs/remote-api-openapi.yaml` (see `server/remoteapi_fastapi` for a drop-in sample).

## Configuration inside the app

- Base URL: defaults to `http://10.0.2.2:8000` for local emulator testing.
- Auth header name + value: optional. Matches `auth.header` from `/v1/capabilities`.
- Page size: server-side page size hint (1–200).

Changes require reopening the source so the preferences reload.

## Behavior

- Dynamic filters come from `/v1/capabilities`.
- If the backend responds with 501 for a feature, the extension shows a friendly error and stops the request.
- Page requests can include per-image headers when the backend sets `headers` in the page list response.
