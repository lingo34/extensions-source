from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

import main


@pytest.fixture()
def client() -> Iterator[TestClient]:
    main.app.dependency_overrides[main.require_api_key] = lambda: None
    test_client = TestClient(main.app)
    try:
        yield test_client
    finally:
        main.app.dependency_overrides.pop(main.require_api_key, None)
        test_client.close()


def test_capabilities_includes_flags_and_defaults(client: TestClient) -> None:
    response = client.get("/v1/capabilities")

    assert response.status_code == 200
    payload = response.json()
    assert payload["supports"]["popular"] is True
    assert payload["defaults"]["page_size"] == 40
    assert any(option["label"] == "Action" for option in payload["filters"][0]["options"])


def test_popular_pagination_respects_page_and_size(client: TestClient) -> None:
    response = client.get("/v1/manga/popular", params={"page": 1, "page_size": 2})

    assert response.status_code == 200
    payload = response.json()
    assert payload["total"] >= 3
    assert len(payload["items"]) == 2
    assert payload["has_next"] is True


def test_search_returns_synthetic_when_no_match(client: TestClient) -> None:
    query = "CompletelyMadeUpTitle"
    response = client.get("/v1/manga/search", params={"query": query})

    assert response.status_code == 200
    payload = response.json()
    assert payload["items"]
    assert payload["items"][0]["title"].startswith(query)


def test_chapter_pages_have_expected_length(client: TestClient) -> None:
    manga_response = client.get("/v1/manga/wanderers")
    assert manga_response.status_code == 200
    first_chapter_id = manga_response.json()["chapters"][0]["id"]

    pages_response = client.get(f"/v1/chapters/{first_chapter_id}/pages")

    assert pages_response.status_code == 200
    payload = pages_response.json()
    assert len(payload["pages"]) == 6
    assert all(
        page["image_url"].startswith("https://picsum.photos/seed/") for page in payload["pages"]
    )


def test_unknown_manga_returns_404(client: TestClient) -> None:
    response = client.get("/v1/manga/not-here")

    assert response.status_code == 404
    assert response.json()["detail"] == "Manga not found"
