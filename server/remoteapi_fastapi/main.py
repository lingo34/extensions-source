"""Reference FastAPI server for the Remote API Tachiyomi extension.

Run locally with:
    uvicorn main:app --reload

All image URLs come from https://picsum.photos for easy testing.
The server keeps everything in memory and is intentionally small but type-strict
so it's easy to extend. Use the OpenAPI document in ../../docs/remote-api-openapi.yaml
for the contract the Kotlin client expects.
"""

from __future__ import annotations

import os
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Generic, Literal, TypeVar, cast

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from pydantic import BaseModel, Field, HttpUrl

app = FastAPI(title="Remote API sample", version="0.1.0")

# ---------------------------------------------------------------------------
# Models (mirror the Kotlin data classes)
# ---------------------------------------------------------------------------


class SupportFlags(BaseModel):
    popular: bool = True
    latest: bool = True
    search: bool = True
    manga_details: bool = True
    chapters: bool = True
    pages: bool = True


class DefaultValues(BaseModel):
    page_size: int | None = Field(None, ge=1, le=200)


class AuthSpec(BaseModel):
    header: str = "X-Api-Key"
    type: str = "apiKey"
    scheme: str = "plain"


class FilterOption(BaseModel):
    value: str
    label: str


class FilterDefinition(BaseModel):
    key: str
    label: str
    type: str
    options: list[FilterOption] = Field(default_factory=list)
    default: str | None = None
    section: str | None = None


class CapabilitiesResponse(BaseModel):
    name: str
    version: str
    supports: SupportFlags = Field(default_factory=lambda: SupportFlags())
    filters: list[FilterDefinition] = Field(default_factory=list)
    auth: AuthSpec = Field(default_factory=lambda: AuthSpec())
    defaults: DefaultValues = Field(default_factory=lambda: DefaultValues(page_size=None))


class RemoteManga(BaseModel):
    id: str
    title: str
    alt_titles: list[str] = Field(default_factory=list)
    url: HttpUrl | None = None
    thumbnail: HttpUrl | None = None
    description: str | None = None
    author: str | None = None
    artist: str | None = None
    status: str | None = None
    tags: list[str] = Field(default_factory=list)
    lang: str | None = None
    nsfw: bool | None = None


class RemoteChapter(BaseModel):
    id: str
    name: str
    url: str | None = None
    number: float | None = None
    volume: str | None = None
    scanlator: str | None = None
    uploaded: int | None = None


class RemotePage(BaseModel):
    index: int | None = None
    image_url: HttpUrl
    page_url: HttpUrl | None = None
    headers: dict[str, str] | None = None


T = TypeVar("T")
PR = TypeVar("PR", bound="PagedResult[Any]")
FeatureName = Literal[
    "popular",
    "latest",
    "search",
    "manga_details",
    "chapters",
    "pages",
]


class PagedResult(BaseModel, Generic[T]):
    items: list[T]
    has_next: bool = False
    total: int | None = None


class PagedManga(PagedResult[RemoteManga]):
    pass


class PagedChapter(PagedResult[RemoteChapter]):
    pass


class MangaEnvelope(BaseModel):
    manga: RemoteManga
    chapters: list[RemoteChapter] | None = None


class PageListResponse(BaseModel):
    pages: list[RemotePage]


# ---------------------------------------------------------------------------
# Data fixtures
# ---------------------------------------------------------------------------


@dataclass
class SeriesFixture:
    manga: RemoteManga
    chapters: list[RemoteChapter]


GENRES = [
    FilterOption(value="action", label="Action"),
    FilterOption(value="romance", label="Romance"),
    FilterOption(value="slice", label="Slice of Life"),
    FilterOption(value="scifi", label="Sci-Fi"),
]

SUPPORT_FLAGS = SupportFlags()
DEFAULT_PAGE_SIZE = 40
DEFAULTS = DefaultValues(page_size=DEFAULT_PAGE_SIZE)

FIXTURES: dict[str, SeriesFixture] = {}


def _seed_data() -> None:
    """Create a few fake series with predictable IDs and chapters."""

    def picsum(seed: str, width: int = 480, height: int = 720) -> HttpUrl:
        return cast(HttpUrl, f"https://picsum.photos/seed/{seed}/{width}/{height}")

    sample_series = [
        (
            "wanderers",
            RemoteManga(
                id="wanderers",
                title="Wanderers of the Rim",
                description="Courier duo crossing a dusty rim world to deliver packages.",
                tags=["action", "scifi"],
                author="I. Nova",
                artist="I. Nova",
                status="ongoing",
                thumbnail=picsum("wanderers"),
                alt_titles=["The Rim Runners"],
            ),
            12,
        ),
        (
            "brewery",
            RemoteManga(
                id="brewery",
                title="The Quiet Brewery",
                description="Slow days, latte art, and a found family inside a microbrewery.",
                tags=["slice", "romance"],
                author="Kai Liu",
                artist="Kai Liu",
                status="hiatus",
                thumbnail=picsum("brewery"),
            ),
            6,
        ),
        (
            "afterstorm",
            RemoteManga(
                id="afterstorm",
                title="After the Storm",
                description="Neighbors rebuild their town after a once-in-a-century typhoon.",
                tags=["slice", "action"],
                author="S. Rocha",
                artist="S. Rocha",
                status="completed",
                thumbnail=picsum("afterstorm"),
            ),
            9,
        ),
    ]

    for series_id, manga, chapter_count in sample_series:
        chapters: list[RemoteChapter] = []
        for num in range(1, chapter_count + 1):
            chapters.append(
                RemoteChapter(
                    id=f"{series_id}-ch{num}",
                    name=f"Chapter {num}",
                    number=float(num),
                    uploaded=1_700_000_000_000 + num * 1000000,
                ),
            )
        FIXTURES[series_id] = SeriesFixture(manga=manga, chapters=chapters)


_seed_data()


# ---------------------------------------------------------------------------
# Auth and support helpers
# ---------------------------------------------------------------------------


def _auth_spec() -> AuthSpec:
    return AuthSpec(header=os.environ.get("REMOTEAPI_API_HEADER", "X-Api-Key"))


def _api_key() -> str | None:
    return os.environ.get("REMOTEAPI_API_KEY")


def require_api_key(request: Request) -> None:
    key = _api_key()
    if key is None:
        return
    header = _auth_spec().header
    provided = request.headers.get(header)
    if provided != key:
        raise HTTPException(status_code=401, detail="Invalid or missing API key")


def ensure(feature: FeatureName) -> None:
    flag_value = getattr(SUPPORT_FLAGS, feature, None)
    if not isinstance(flag_value, bool):
        raise HTTPException(
            status_code=500,
            detail=(
                f"Support flag '{feature}' is not a boolean; found {type(flag_value).__name__}"
            ),
        )
    if not flag_value:
        raise HTTPException(status_code=501, detail=f"Feature '{feature}' is disabled by server")


def _last_upload_timestamp(fixture: SeriesFixture) -> int:
    if not fixture.chapters:
        return -1
    last_uploaded = fixture.chapters[-1].uploaded
    return last_uploaded if last_uploaded is not None else -1


def _slug(text: str) -> str:
    return "".join(ch.lower() for ch in text if ch.isalnum()) or "item"


def _ensure_synthetic(query: str) -> SeriesFixture:
    """Create a placeholder series so any query returns at least one item."""
    sid = f"synthetic-{_slug(query)}"

    def picsum(seed: str, width: int = 480, height: int = 720) -> HttpUrl:
        return cast(HttpUrl, f"https://picsum.photos/seed/{seed}/{width}/{height}")

    manga = RemoteManga(
        id=sid,
        title=query.strip() or "Untitled",
        description=f"Placeholder result automatically generated for '{query}'.",
        tags=["generated"],
        author="Remote API",
        artist="Remote API",
        status="ongoing",
        thumbnail=picsum(sid),
    )
    chapters = [
        RemoteChapter(
            id=f"{sid}-ch{num}",
            name=f"Chapter {num}",
            number=float(num),
            uploaded=1_700_000_000_000 + num * 100000,
        )
        for num in range(1, 4)
    ]
    return SeriesFixture(manga=manga, chapters=chapters)


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------


@app.get("/v1/capabilities", response_model=CapabilitiesResponse)
async def capabilities() -> CapabilitiesResponse:
    return CapabilitiesResponse(
        name="Remote API sample",
        version="0.1.0",
        supports=SUPPORT_FLAGS,
        auth=_auth_spec(),
        defaults=DefaultValues(page_size=DEFAULT_PAGE_SIZE),
        filters=[
            FilterDefinition(
                key="genre",
                label="Genre",
                type="select",
                options=GENRES,
                default="action",
            ),
            FilterDefinition(key="tag", label="Tag contains", type="text"),
            FilterDefinition(key="nsfw", label="Include NSFW", type="checkbox", default="false"),
            FilterDefinition(
                key="sort",
                label="Sort",
                type="sort",
                options=[
                    FilterOption(value="updated", label="Updated"),
                    FilterOption(value="title", label="Title"),
                ],
                default="updated:desc",
            ),
        ],
    )


@app.get("/v1/manga/popular", response_model=PagedManga)
async def popular_manga(
    _request: Request,
    page: int = Query(1, ge=1),
    page_size: int = Query(DEFAULT_PAGE_SIZE, ge=1, le=200),
    _api_key_check: None = Depends(require_api_key),
) -> PagedManga:
    ensure("popular")
    sorted_items = sorted(
        FIXTURES.values(), key=lambda fixture: len(fixture.chapters), reverse=True
    )
    return _paginate(
        [fixture.manga for fixture in sorted_items],
        page,
        page_size,
        model_cls=PagedManga,
    )


@app.get("/v1/manga/latest", response_model=PagedManga)
async def latest_manga(
    _request: Request,
    page: int = Query(1, ge=1),
    page_size: int = Query(DEFAULT_PAGE_SIZE, ge=1, le=200),
    _api_key_check: None = Depends(require_api_key),
) -> PagedManga:
    ensure("latest")
    sorted_items = sorted(
        FIXTURES.values(),
        key=_last_upload_timestamp,
        reverse=True,
    )
    return _paginate(
        [fixture.manga for fixture in sorted_items],
        page,
        page_size,
        model_cls=PagedManga,
    )


@app.get("/v1/manga/search", response_model=PagedManga)
async def search_manga(
    _request: Request,
    query: str | None = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(DEFAULT_PAGE_SIZE, ge=1, le=200),
    filter_genre: str | None = Query(None, alias="filter.genre"),
    filter_tag: str | None = Query(None, alias="filter.tag"),
    filter_nsfw: str | None = Query(None, alias="filter.nsfw"),
    sort: str | None = None,
    order: str | None = None,
    _api_key_check: None = Depends(require_api_key),
) -> PagedManga:
    ensure("search")
    fixtures: list[SeriesFixture] = list(FIXTURES.values())

    if query:
        q = query.lower()
        fixtures = [
            fx
            for fx in fixtures
            if q in fx.manga.title.lower() or q in (fx.manga.description or "").lower()
        ]

    if query and not fixtures:
        fixtures = [_ensure_synthetic(query)]

    if filter_genre:
        fixtures = [fx for fx in fixtures if filter_genre in fx.manga.tags]
    if filter_tag:
        fixtures = [
            fx for fx in fixtures if any(filter_tag.lower() in tag.lower() for tag in fx.manga.tags)
        ]
    if filter_nsfw == "true":
        pass  # sample data is SFW, but we keep the switch for compatibility

    if sort == "title":
        fixtures.sort(key=lambda fx: fx.manga.title.lower())
    else:
        fixtures.sort(key=_last_upload_timestamp, reverse=True)
    if order == "asc":
        fixtures.reverse()

    mangas = [fx.manga for fx in fixtures]
    return _paginate(mangas, page, page_size, model_cls=PagedManga)


@app.get("/v1/manga/{manga_id}", response_model=MangaEnvelope)
async def manga_details(
    manga_id: str, _request: Request, _api_key_check: None = Depends(require_api_key)
) -> MangaEnvelope:
    ensure("manga_details")
    fixture = FIXTURES.get(manga_id)
    if not fixture and manga_id.startswith("synthetic-"):
        fixture = _ensure_synthetic(manga_id.removeprefix("synthetic-"))
    if not fixture:
        raise HTTPException(status_code=404, detail="Manga not found")
    return MangaEnvelope(manga=fixture.manga, chapters=fixture.chapters)


@app.get("/v1/manga/{manga_id}/chapters", response_model=PagedChapter)
async def chapters(
    manga_id: str,
    _request: Request,
    page: int = Query(1, ge=1),
    page_size: int = Query(DEFAULT_PAGE_SIZE, ge=1, le=200),
    _api_key_check: None = Depends(require_api_key),
) -> PagedChapter:
    ensure("chapters")
    fixture = FIXTURES.get(manga_id)
    if not fixture and manga_id.startswith("synthetic-"):
        fixture = _ensure_synthetic(manga_id.removeprefix("synthetic-"))
    if not fixture:
        raise HTTPException(status_code=404, detail="Manga not found")
    return _paginate(fixture.chapters, page, page_size, model_cls=PagedChapter)


@app.get("/v1/chapters/{chapter_id}/pages", response_model=PageListResponse)
async def pages(
    chapter_id: str, _request: Request, _api_key_check: None = Depends(require_api_key)
) -> PageListResponse:
    ensure("pages")
    parent = next(
        (fx for fx in FIXTURES.values() if any(ch.id == chapter_id for ch in fx.chapters)),
        None,
    )
    if not parent:
        raise HTTPException(status_code=404, detail="Chapter not found")
    pages: list[RemotePage] = []
    for index in range(6):
        pages.append(
            RemotePage(
                index=index,
                image_url=cast(
                    HttpUrl,
                    f"https://picsum.photos/seed/{chapter_id}-{index}/1200/1800",
                ),
            ),
        )
    return PageListResponse(pages=pages)


# ---------------------------------------------------------------------------
# Utils
# ---------------------------------------------------------------------------


def _paginate(
    items: Sequence[T],
    page: int,
    page_size: int,
    model_cls: type[PR] | None = None,
) -> PR:
    start = (page - 1) * page_size
    end = start + page_size
    sliced = list(items[start:end])
    cls = cast(type[PR], model_cls or PagedResult)
    return cls(items=sliced, has_next=end < len(items), total=len(items))


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", reload=True)
