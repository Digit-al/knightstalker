# KnightStalker — Local Media Index Architecture

**Status:** Proposed · **Author:** architecture review · **Scope:** Movies (VOD) + Series browsing

Goal: replace network-paged, server-sorted, server-filtered browsing with a **local
index** (a Room/SQLite mirror) so that *filtering + sorting + full-text search* combine
in a single instant query, with no page-by-page network round-trips and no dependence on
the portal's broken sort/filter behaviour. Separate indexes for Movies and Series, each
refreshed when its tab is opened.

---

## 1. Why — the evidence

We tested the live portal (`ethan.oulens.com`, MAG handshake) directly:

| Test | Result | Implication |
|------|--------|-------------|
| `get_ordered_list category=473 sortby=rating` | **Identical to `sortby=added`** — order ignored, ratings not descending (7.7, 7.5, 7.6, 6.9…) | Server **does not** sort by rating within a category |
| `get_ordered_list category=* sortby=rating` | `Wonka (7.1)` then a wall of `xXx N/A` items | Global rating sort is **garbage** (N/A floats to top, adult dominates) |
| `sortby=year` | Reorders, but inconsistently across `*` | Only partially honoured |
| Filtering "no adult" / "has rating" | **No such parameter exists** | Adult is just another category; "All" = union of everything including `|XX| ADULT`. There is no `min_rating`/`has_rating`. |
| Page size | `max_page_items = 14`, fixed | 76,069 movies = **5,434 pages**; 15,553 series = **1,111 pages** |

**Conclusion:** correct "top-rated, no-adult, no-N/A across the whole catalogue" is
*impossible* as a cheap server query on this panel. Doing it client-side over an
*unsorted* network stream is what caused the runaway pagination and the "1 result"
(Wonka) bug. The only robust fix is to **own the data locally**.

### What a single crawl actually yields (confirmed from the raw payload)

Every list item already contains everything we need — no per-item N+1 calls:

```
id, name, o_name (original), cmd, category_id, added (timestamp),
year (date), rating_imdb, rating_kinopoisk, rate, genres_str,
genre_id_1..4, cat_genre_id_1..4, actors, director, description,
screenshot_uri / pic, tmdb_id, is_series, is_movie, censored, time (duration)
```

So **one `category=*` crawl per kind** populates a fully-attributed index, including each
item's category membership and added-date. That is the linchpin of this design.

---

## 2. Goals / non-goals

**Goals**
- Combined **filter + sort + search** resolved in one local SQL query, < ~30 ms over 76k rows.
- **No network paging** in the browse path — UI pages over the local DB (Paging 3).
- **Performant search** (multi-word, accent-insensitive, prefix) via SQLite **FTS5**.
- **Separate** Movies and Series indexes (schema, sync cursor, FTS, state all independent).
- **Refresh on tab open**: full build first time, fast delta thereafter; survives app close.
- Resumable, token-expiry-tolerant, multi-portal-safe.

**Non-goals**
- Series *episodes* are **not** indexed (fetched on demand when a series is opened). The
  series index holds series-level rows only.
- Live TV (ITV) is out of scope — it already sorts by `number` acceptably.
- No attempt to "fix" the server; we bypass it.

---

## 3. High-level shape

```
        ┌─────────────────────────── on Movies tab opened ──────────────────────────┐
        │                                                                            │
   ┌────▼─────┐   crawl/delta    ┌──────────────┐   upsert (batched tx)   ┌──────────▼─────────┐
   │ Stalker  │◀────────────────▶│  SyncEngine  │────────────────────────▶│  Room: movie_index │
   │  portal  │  get_ordered_list│ (WorkManager)│                         │  + movie_fts (FTS5)│
   └──────────┘                  └──────────────┘                         └──────────┬─────────┘
                                                                                     │ PagingSource
                                  filter+sort+search = ONE query  ┌──────────────────▼─────────┐
                                                                  │   VodViewModel (local-only)│
                                                                  └────────────────────────────┘
```

- **Browse path** never touches the network. It binds a Compose `LazyVerticalGrid` to a
  Room `PagingSource` produced by a single parameterised query.
- **Sync path** is the only network consumer for lists. It writes into Room; the UI
  observes Room and updates live as rows land.
- Movies and Series are two parallel instances of the same machinery.

---

## 4. Data model (Room)

Two structurally-identical tables (honouring "separate for movies and series"), each with
its own external-content FTS5 table, plus one shared sync-state table keyed by kind.

### 4.1 Index entity

```kotlin
@Entity(
    tableName = "movie_index",
    primaryKey = ["portalId", "itemId"],
    indices = [
        Index(value = ["portalId", "categoryId"]),
        Index(value = ["portalId", "isAdult", "rating"]),
        Index(value = ["portalId", "isAdult", "year"]),
        Index(value = ["portalId", "isAdult", "addedAt"]),
        Index(value = ["portalId", "categoryId", "isAdult", "rating"]),
    ]
)
data class MovieIndexEntity(
    val portalId: Long,          // multi-portal safe
    val itemId: String,          // Stalker item id
    val name: String,            // display name (markers kept)
    val sortName: String,        // name w/ |FR| / |xXx| markers & accents stripped, lowercased
    val cmd: String,             // for create_link (play / download)
    val categoryId: String,
    val rating: Double?,         // parsed, NULL if unrated  (NULL sorts last, not "N/A first")
    val year: Int?,              // parsed 4-digit, NULL if unknown
    val addedAt: Long,           // epoch millis from `added`
    val genresCsv: String,       // genres_str, for genre filter / display
    val durationMin: Int?,       // `time`
    val posterUrl: String,       // screenshot_uri / pic
    val isAdult: Boolean,        // derived once at index time (see §8)
    val tmdbId: String?,         // future enrichment
    val syncEpoch: Long          // stamp of last crawl that saw this row → prune removed
)
```

`SeriesIndexEntity` is identical with `tableName = "series_index"` and FTS counterpart
`series_fts`. Share a Kotlin `interface MediaIndexRow` so mappers/DTO→entity and the
domain layer are written once.

> **Why denormalise `categoryId` onto the row** rather than a join table: an item has a
> single `category_id` in this portal, queries always filter by at most one category, and
> denormalising lets the `(categoryId, isAdult, rating)` composite index serve the hot
> path with zero joins.

### 4.2 FTS5 search table (external content)

```sql
CREATE VIRTUAL TABLE movie_fts USING fts5(
    name, actors, director,
    content='movie_index', content_rowid='rowid',
    tokenize = "unicode61 remove_diacritics 2"
);
```

- `remove_diacritics 2` → "amelie" matches "Amélie".
- External-content (`content='movie_index'`) means FTS stores only the inverted index, not
  a copy of the text → ~half the size. Kept in sync by triggers on insert/update/delete.
- `actors`/`director` live in FTS only (searchable) and are **not** columns on the main
  table (they're long and never sorted/filtered on) — keep the row narrow and the
  `(...)` indexes hot. `description` is intentionally **not** indexed (noise + size).

### 4.3 Sync state

```kotlin
@Entity(tableName = "media_sync_state")  // primaryKey = "kind"  ("movie" | "series")
data class SyncStateEntity(
    val kind: String,
    val status: String,        // IDLE | CRAWLING | DELTA | ERROR
    val totalItemsRemote: Int, // from total_items, for progress %
    val itemsIndexed: Int,
    val crawlCursorPage: Int,  // resume point for an interrupted full crawl
    val lastFullSyncAt: Long,  // 0 = never → triggers first-run full crawl
    val lastDeltaSyncAt: Long,
    val syncEpoch: Long,       // current crawl's stamp (for prune sweep)
    val lastError: String?
)
```

---

## 5. The one query that replaces the backend

All browsing — category select, sort dropdown, adult toggle, "rated only", search box —
collapses into a **single** parameterised statement returning a `PagingSource`:

```sql
SELECT * FROM movie_index
WHERE portalId = :portalId
  AND (:categoryId IS NULL OR categoryId = :categoryId)
  AND (:excludeAdult = 0 OR isAdult = 0)
  AND (:ratedOnly   = 0 OR rating IS NOT NULL)
  AND (:minYear IS NULL OR year >= :minYear)
  AND (:hasQuery = 0 OR rowid IN (
        SELECT rowid FROM movie_fts WHERE movie_fts MATCH :ftsQuery
      ))
ORDER BY
  CASE WHEN :sort = 'RATING' THEN rating END DESC,
  CASE WHEN :sort = 'YEAR'   THEN year   END DESC,
  CASE WHEN :sort = 'ADDED'  THEN addedAt END DESC,
  sortName ASC                       -- stable tiebreaker / NAME sort
LIMIT :limit OFFSET :offset;
```

- `rating IS NULL` naturally sorts **last** under `DESC` → no more "N/A first".
- Search term → `:ftsQuery = "amelie*"` (debounced, last token gets a `*` for prefix).
  Multi-word becomes `"jolie* coutures*"` (implicit AND).
- Room returns a `PagingSource<Int, MovieIndexEntity>`; Compose consumes it with
  `collectAsLazyPagingItems()` — **lazy rendering over local rows**, never the network.
- Typical cost: index-backed filter + FTS rowid set + sort on an indexed column over 76k
  rows → single-digit-to-low-tens of milliseconds on the TV box.

DAO sketch:

```kotlin
@Query(/* the SQL above */)
fun pageMovies(
    portalId: Long, categoryId: String?, excludeAdult: Int, ratedOnly: Int,
    minYear: Int?, hasQuery: Int, ftsQuery: String, sort: String
): PagingSource<Int, MovieIndexEntity>
```

---

## 6. Sync engine

One engine, parameterised by `kind`. Three operations:

### 6.1 Full crawl (first run, or manual rebuild, or reconcile)

```
stamp = now()                       // this crawl's syncEpoch
page  = state.crawlCursorPage ?: 1  // resume if interrupted
loop:
    js = get_ordered_list(kind, category="*", sortby="added", p=page)
    total = js.total_items;  upsert state.totalItemsRemote = total
    if js.data empty: break
    batch:                          // single Room transaction per page-group (~500 rows)
        for item in js.data: upsert(toEntity(item, stamp))
    state.crawlCursorPage = page; state.itemsIndexed += js.data.size
    if page * 14 >= total: break
    page += 1
prune: DELETE FROM <kind>_index WHERE portalId=? AND syncEpoch < stamp   // removed upstream
state.lastFullSyncAt = now(); status = IDLE; crawlCursorPage = 0
```

- **`sortby=added`** so the crawl is newest-first; lets delta reuse the same ordering.
- **Concurrency:** crawl is range-partitioned — dispatch N pages in flight (recommend
  **6–8**) through a bounded dispatcher; each completed page upserts. Backpressure via a
  semaphore. (Strictly sequential also works but is ~6× slower.)
- **Resumable:** `crawlCursorPage` persisted → process death / app close resumes, doesn't
  restart. This is why the heavy crawl runs in **WorkManager** (unique, long-running,
  `setExpedited` best-effort), not a viewModelScope job.
- **Prune sweep** by `syncEpoch` tombstoning removes items deleted upstream — cheap and
  correct, no per-item existence checks.

### 6.2 Delta sync (every qualifying tab open)

The catalogue grows at the front (`added` desc). So:

```
stamp = now()
page = 1
loop:
    js = get_ordered_list(kind, category="*", sortby="added", p=page)
    new = 0
    for item in js.data:
        if not exists(itemId) or changed(item): upsert(toEntity(item, stamp)); new++
    if new == 0: break          // hit the unchanged tail → stop
    if page >= DELTA_MAX_PAGES (e.g. 40): break   // safety cap
    page += 1
state.lastDeltaSyncAt = now()
```

- Usually completes in **1–3 pages** (< 1 s). No prune here (delta can't see removals);
  removals are caught by the periodic full reconcile.
- A **periodic full reconcile** runs when `now - lastFullSyncAt > 7 days` (or on manual
  "Rebuild library"), reusing §6.1.

### 6.3 Token / auth handling

Sync borrows the existing `StalkerAuthManager`. On `401/empty-js` mid-crawl → re-handshake
once, then continue from `crawlCursorPage`. Reuses the app's interceptors (MAC cookie,
bearer) verbatim — no new auth code.

---

## 7. "Update on opening the corresponding page" — state machine

On `Movies` (resp. `Series`) tab entering composition, the ViewModel calls
`syncEngine.ensureFresh(kind)`:

```
state = syncStateDao.get(kind)
when:
  lastFullSyncAt == 0        -> enqueue FULL crawl (WorkManager). UI shows local rows as
                                they arrive + "Indexing 12,340 / 76,069…" banner.
  now - lastDeltaSyncAt < T  -> do nothing (T ≈ 15 min throttle; avoids re-sync on every
                                tab flip). UI shows current local index instantly.
  else                       -> launch DELTA sync (background). UI instant from local;
                                new rows trickle in.
  now - lastFullSyncAt > 7d  -> also schedule a reconcile FULL crawl (low priority).
```

Crucially the **grid binds to Room from frame one** — whether the index has 0 or 76k rows,
the UI is responsive; sync only *adds* rows underneath the live `PagingSource`.

---

## 8. Normalisation rules (decided once, at index time)

- **rating**: try `rating_imdb` then `rating_kinopoisk` then `rate`; parse tolerant of
  `"7,4"`→`7.4`, `"N/A"`/`""`/`"0"`→`NULL`. Clamp to 0–10.
- **year**: `year` may be `"2022"` or `"2022-12-14"` → take leading 4 digits → Int or NULL.
- **addedAt**: parse `"yyyy-MM-dd HH:mm:ss"` (portal local tz) → epoch millis; fallback 0.
- **isAdult** (belt-and-suspenders, computed once):
  `categoryId ∈ adultCategoryIds`  **OR**  `name` contains `|xXx`/`xXx|` (case-insens.)
  **OR** `censored == 1`. `adultCategoryIds` resolved from `get_categories` (titles
  containing `ADULT`/`XXX`). Stored as the boolean column so query-time filtering is free.
- **sortName**: strip leading `|XX|`/`|FR|`… markers, strip accents, lowercase → used for
  NAME sort and as the stable tiebreaker.

---

## 9. Separation & multi-portal

- **Movies vs Series**: distinct tables, FTS, sync cursors, WorkManager unique-work names
  (`sync-movie`, `sync-series`), and ViewModels (`VodViewModel` mode Vod/Series already
  exists). They never share state; opening one tab syncs only that kind.
- **Multi-portal**: every row and query is keyed by `portalId`. Switching the active
  portal simply changes the `:portalId` bind — each portal keeps its own index. A portal's
  index can be dropped on portal delete (`DELETE … WHERE portalId=?`).

---

## 10. UI integration & migration

1. Land the index + sync engine **behind the scenes** first; keep the current network path.
2. Switch `VodViewModel` browse reads to the local DAO `PagingSource`. The existing sort
   dropdown, search box, category sidebar, adult toggle, and the new "rated only" become
   pure query parameters — most of the client-side filter/sort/pagination-cap code in
   `VodViewModel` (the `MAX_RATED_RESULTS`/`MAX_SCAN_PAGES` hacks, `sortItems`,
   `hasRating`, accumulate-pages logic) is **deleted**.
3. `create_link` (play/download) is unchanged — still on-demand from `cmd`.
4. **Fallback:** if the index is empty and a crawl can't run (offline/auth fail), fall back
   to today's network paging so the app degrades gracefully rather than showing nothing.

---

## 11. Performance & sizing budget

| | Movies | Series |
|---|--:|--:|
| Items | 76,069 | 15,553 |
| Pages (÷14) | 5,434 | 1,111 |
| Full crawl @ 8-concurrent, ~300 ms/req | ~3.5 min | ~45 s |
| Delta (steady state) | 1–3 req, < 1 s | 1–3 req, < 1 s |
| Row size (narrow schema, no description) | ~250–350 B | ~250–350 B |
| Est. DB incl. FTS | ~30–45 MB | ~7–10 MB |
| Browse query latency | single-digit–low-tens ms | same |

One-time ~6.5k requests for the initial build; everything after is sub-second. Acceptable
for a wired/Wi-Fi TV box; the crawl is backgrounded and resumable so the user browses
immediately.

---

## 12. Failure modes & mitigations

| Risk | Mitigation |
|---|---|
| Crawl interrupted (app killed) | `crawlCursorPage` resume + WorkManager retry |
| Token expires mid-crawl | re-handshake once, continue from cursor |
| Portal rate-limits the crawl | bounded concurrency (6–8) + small jitter; expose concurrency as a constant |
| `total_items` drifts during crawl | loop stops on empty page, not just the count; reconcile fixes drift |
| Upstream removals | `syncEpoch` prune sweep on full reconcile |
| Rating/year dirty data | tolerant parsers → NULL, never crash |
| Mixed JSON types (`rating` string vs number) | DTO field as `JsonElement`/`String`, parse defensively |
| DB corruption / schema change | versioned Room migrations; "Rebuild library" nukes & re-crawls |
| First-run UX on huge catalogue | progressive fill + progress banner; never a blank blocking spinner |

---

## 13. Settings & observability

- **Content** card (already added) gains: "Library" subsection — last sync time, item
  count, index size, **Rebuild library now**, and a **Auto-update on open** toggle.
- Sync status surfaced as the small banner on Movies/Series while `CRAWLING`/`DELTA`.

---

## 14. Phased delivery

| Phase | Deliverable | Notes |
|---|---|---|
| **P1 — Storage** | Room entities (movie/series), FTS5 tables + triggers, sync-state, migrations, DTO→entity mappers with §8 normalisation | No behaviour change yet; unit-test the parsers |
| **P2 — Sync engine** | WorkManager full crawl (resumable, concurrent, prune), delta sync, `ensureFresh()` state machine, token re-handshake | Verifiable via logs / row counts |
| **P3 — Browse switch** | `VodViewModel` reads local `PagingSource`; wire the combined query to sort/search/category/adult/rated filters; delete the client-side sort/pagination hacks; network fallback | The user-visible win lands here |
| **P4 — Polish** | Settings library card, progress banner, periodic reconcile, multi-portal drop-on-delete, metrics | — |

---

## 15. Open decisions (need a call)

1. **Auto-crawl trigger** — full crawl automatically on first Movies open (recommended,
   progressive UI), or gate behind an explicit "Build library" button the first time?
   (Trade-off: ~3.5 min background traffic vs. zero surprise.)
2. **Concurrency** for the crawl — 8 is the proposed default; lower if the panel is touchy.
3. **Keep `description` searchable?** Proposed: no (size/noise). Name+actors+director only.
4. **Reconcile cadence** — 7 days proposed for the full removal-detecting reconcile.
5. **Storage ceiling** — both indexes ~40–55 MB total; acceptable on the Z12? (Yes, but
   worth confirming there's no tight storage policy.)

---

### TL;DR
Crawl each kind once into a Room mirror (`category=*`, every field we need is already in
the list payload), keep it fresh with a 1–3-request delta on tab open, and serve all
browsing from **one** FTS5-backed SQL query. That deletes the page-by-page network model,
sidesteps the portal's broken sort/filter, and makes combined filter + sort + search
instant — separately for Movies and Series.
