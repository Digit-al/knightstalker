# KnightStalker — Local Media Index: IMPLEMENTATION CONTRACT

**Status:** BINDING. Source of truth for 4 parallel implementers (Phases: Storage, Sync, Browse, Wiring/Gradle).
**Companion:** `docs/local-index-architecture.md` (rationale). Where this contract and the arch doc differ, **this contract wins** (notably: Room `@Fts4(contentEntity=...)` forces `actors`/`director` to be columns on the index entity, and the index lives in a **separate** `media_index.db`).

Build command — use EXACTLY this, nothing else:

```
cd /home/ashigaru/knightstalker && export ANDROID_HOME=/home/ashigaru/Android/Sdk ANDROID_SDK_ROOT=/home/ashigaru/Android/Sdk && /tmp/claude-1000/-home-ashigaru-knightstalker/a7221199-5e4d-4185-b048-863a5ccdfa67/scratchpad/gradle-8.5/bin/gradle assembleDebug 2>&1 | tail -50
```

Package root: `org.knightstalker.app`. DI is MANUAL via `AppModule` — no Hilt. NEVER touch/migrate `knightstalker.db` (portals/favorites/watch_history). The index is a rebuildable cache in its OWN DB and MAY use `fallbackToDestructiveMigration`.

---

## 0. File manifest

### NEW files (create)

| Path | Purpose |
|---|---|
| `app/src/main/java/org/knightstalker/app/data/local/entity/MediaIndexRow.kt` | Shared interface `MediaIndexRow` |
| `app/src/main/java/org/knightstalker/app/data/local/entity/MovieIndexEntity.kt` | `movie_index` entity |
| `app/src/main/java/org/knightstalker/app/data/local/entity/SeriesIndexEntity.kt` | `series_index` entity |
| `app/src/main/java/org/knightstalker/app/data/local/entity/MovieFtsEntity.kt` | `@Fts4` `movie_fts` |
| `app/src/main/java/org/knightstalker/app/data/local/entity/SeriesFtsEntity.kt` | `@Fts4` `series_fts` |
| `app/src/main/java/org/knightstalker/app/data/local/entity/SyncStateEntity.kt` | `media_sync_state` entity |
| `app/src/main/java/org/knightstalker/app/data/local/db/MovieIndexDao.kt` | Movie DAO (paging + writes) |
| `app/src/main/java/org/knightstalker/app/data/local/db/SeriesIndexDao.kt` | Series DAO (paging + writes) |
| `app/src/main/java/org/knightstalker/app/data/local/db/SyncStateDao.kt` | Sync-state DAO |
| `app/src/main/java/org/knightstalker/app/data/local/db/MediaIndexDatabase.kt` | `media_index.db` RoomDatabase |
| `app/src/main/java/org/knightstalker/app/data/local/db/FtsQuery.kt` | `object FtsQuery { fun build(raw): String }` |
| `app/src/main/java/org/knightstalker/app/data/local/sync/MediaKind.kt` | `enum class MediaKind` |
| `app/src/main/java/org/knightstalker/app/data/local/sync/SyncConstants.kt` | tuning constants |
| `app/src/main/java/org/knightstalker/app/data/local/sync/MediaIndexMapper.kt` | DTO→entity + normalization helpers |
| `app/src/main/java/org/knightstalker/app/data/local/sync/SyncEngine.kt` | crawl/delta/ensureFresh engine |
| `app/src/main/java/org/knightstalker/app/data/local/sync/MovieSyncWorker.kt` | WorkManager worker (movies) |
| `app/src/main/java/org/knightstalker/app/data/local/sync/SeriesSyncWorker.kt` | WorkManager worker (series) |
| `app/src/main/java/org/knightstalker/app/data/repository/MediaIndexRepository.kt` | Repo wrapper over the 3 DAOs |

### EXISTING files to MODIFY

| Path | Change |
|---|---|
| `gradle/libs.versions.toml` | add paging/work versions + library aliases |
| `app/build.gradle.kts` | add 4 `implementation(...)` deps |
| `app/src/main/java/org/knightstalker/app/data/remote/dto/StalkerResponses.kt` | extend `VodData` with new `@SerialName` fields |
| `app/src/main/java/org/knightstalker/app/di/AppModule.kt` | add DB/DAOs/repo/engine singletons |
| `app/src/main/java/org/knightstalker/app/ui/vod/VodViewModel.kt` | swap to Paging; delete client-side hacks; new ctor deps |
| `app/src/main/java/org/knightstalker/app/ui/vod/VodScreen.kt` | bind `LazyPagingItems` + indexing banner |
| `app/src/main/java/org/knightstalker/app/ui/navigation/KnightNavGraph.kt` | pass new ctor args at both `composable("vod")` / `composable("series")` |

**Do NOT modify** `KnightDatabase.kt` (existing user DB stays at version 2).

---

## 1. Gradle (Phase: Storage owns this; land FIRST)

`gradle/libs.versions.toml` — add to `[versions]` (after line 8 `room = "2.6.1"`):

```toml
paging = "3.2.1"
work = "2.9.0"
```

Add to `[libraries]` (after the three room entries, after line 40):

```toml
room-paging = { group = "androidx.room", name = "room-paging", version.ref = "room" }
paging-runtime-ktx = { group = "androidx.paging", name = "paging-runtime-ktx", version.ref = "paging" }
paging-compose = { group = "androidx.paging", name = "paging-compose", version.ref = "paging" }
work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
```

`app/build.gradle.kts` — add inside `dependencies {}` immediately after line 80 (`ksp(libs.room.compiler)`):

```kotlin
    implementation(libs.room.paging)
    implementation(libs.paging.runtime.ktx)
    implementation(libs.paging.compose)
    implementation(libs.work.runtime.ktx)
```

(Version-catalog style, matching the existing project. WorkManager 2.9.0 is minSdk-24 safe.)

---

## 2. DTO additions (Phase: Storage) — `StalkerResponses.kt`

Replace the `VodData` class (lines 114–128) with the version below. Existing fields keep their exact names; **append** the new ones. All new fields default-valued so lenient parsing never crashes. `is_series`/`is_movie`/`censored` are `Int` (matches existing `censored` style on `VodCategoryData`).

```kotlin
@Serializable
data class VodData(
    val id: String = "",
    val name: String = "",
    val cmd: String = "",
    @SerialName("screenshot_uri") val screenshotUri: String = "",
    @SerialName("description") val description: String = "",
    val year: String = "",
    @SerialName("genre_str") val genreStr: String = "",
    @SerialName("rating_imdb") val ratingImdb: String = "",
    @SerialName("rating_kinopoisk") val ratingKinopoisk: String = "",
    val time: String = "",
    val director: String = "",
    val actors: String = "",
    // --- added for the local index ---
    @SerialName("o_name") val oName: String = "",
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("added") val added: String = "",
    @SerialName("rate") val rate: String = "",
    @SerialName("tmdb_id") val tmdbId: String = "",
    @SerialName("is_series") val isSeries: Int = 0,
    @SerialName("is_movie") val isMovie: Int = 0,
    @SerialName("censored") val censored: Int = 0
)
```

---

## 3. Entities (Phase: Storage)

### 3.1 `MediaIndexRow` (shared interface — FINAL, identical column set across both tables)

```kotlin
package org.knightstalker.app.data.local.entity

interface MediaIndexRow {
    val portalId: Long
    val itemId: String
    val name: String
    val sortName: String
    val cmd: String
    val categoryId: String
    val rating: Double?
    val year: Int?
    val addedAt: Long
    val genresCsv: String
    val durationMin: Int?
    val posterUrl: String
    val isAdult: Boolean
    val tmdbId: String?
    val actors: String
    val director: String
    val syncEpoch: Long
}
```

> **Binding deviation from arch doc §4.2:** `actors` and `director` ARE columns on the index entity (not FTS-only). Room's `@Fts4(contentEntity=...)` generates the sync triggers by copying named columns out of the content table, so those columns MUST exist on the content entity. This is the only way to honor "use Room `@Fts4(contentEntity=...)`, do not hand-roll FTS5." They are never sorted/filtered on and have no `@Index`.

### 3.2 `MovieIndexEntity` (`series_index` is byte-for-byte identical except `tableName` and class name)

```kotlin
@Entity(
    tableName = "movie_index",
    primaryKeys = ["portalId", "itemId"],
    indices = [
        Index(value = ["portalId", "categoryId"]),
        Index(value = ["portalId", "isAdult", "rating"]),
        Index(value = ["portalId", "isAdult", "year"]),
        Index(value = ["portalId", "isAdult", "addedAt"]),
        Index(value = ["portalId", "categoryId", "isAdult", "rating"]),
        Index(value = ["portalId", "syncEpoch"])
    ]
)
data class MovieIndexEntity(
    override val portalId: Long,
    override val itemId: String,
    override val name: String,
    override val sortName: String,
    override val cmd: String,
    override val categoryId: String,
    override val rating: Double?,
    override val year: Int?,
    override val addedAt: Long,
    override val genresCsv: String,
    override val durationMin: Int?,
    override val posterUrl: String,
    override val isAdult: Boolean,
    override val tmdbId: String?,
    override val actors: String,
    override val director: String,
    override val syncEpoch: Long
) : MediaIndexRow
```

`SeriesIndexEntity`: same fields/types/order/annotations, `tableName = "series_index"`, implements `MediaIndexRow`.

Column SQL types (Room-derived, for reference): `portalId` INTEGER NOT NULL, `itemId` TEXT NOT NULL, `name`/`sortName`/`cmd`/`categoryId`/`genresCsv`/`posterUrl`/`actors`/`director` TEXT NOT NULL, `rating` REAL (nullable), `year` INTEGER (nullable), `addedAt` INTEGER NOT NULL, `durationMin` INTEGER (nullable), `isAdult` INTEGER NOT NULL, `tmdbId` TEXT (nullable), `syncEpoch` INTEGER NOT NULL. PK = (`portalId`,`itemId`); implicit `rowid` is used as the FTS `content_rowid`.

### 3.3 FTS entities

```kotlin
@Fts4(contentEntity = MovieIndexEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "movie_fts")
data class MovieFtsEntity(
    val name: String,
    val actors: String,
    val director: String
)
```

`SeriesFtsEntity`: same, `contentEntity = SeriesIndexEntity::class`, `tableName = "series_fts"`.

- Imports: `androidx.room.Fts4`, `androidx.room.FtsOptions`.
- Do NOT add a manual `rowid` field; Room manages it for content-entity FTS.
- `unicode61` gives accent-insensitive matching; Room generates the insert/update/delete sync triggers automatically. Do NOT hand-write virtual tables or triggers.

### 3.4 `SyncStateEntity`

```kotlin
@Entity(tableName = "media_sync_state")
data class SyncStateEntity(
    @PrimaryKey val kind: String,        // "movie" | "series"
    val status: String,                  // "IDLE" | "CRAWLING" | "DELTA" | "ERROR"
    val totalItemsRemote: Int = 0,
    val itemsIndexed: Int = 0,
    val crawlCursorPage: Int = 0,        // resume point; 0 = fresh
    val lastFullSyncAt: Long = 0,        // 0 = never -> forces first-run full crawl
    val lastDeltaSyncAt: Long = 0,
    val syncEpoch: Long = 0,
    val lastError: String? = null
)
```

---

## 4. DAOs (Phase: Storage)

### 4.1 `MovieIndexDao` (`SeriesIndexDao` identical with `movie_*`→`series_*` and `MovieIndexEntity`→`SeriesIndexEntity`)

```kotlin
@Dao
interface MovieIndexDao {

    @Query("""
        SELECT * FROM movie_index
        WHERE portalId = :portalId
          AND (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:excludeAdult = 0 OR isAdult = 0)
          AND (:ratedOnly = 0 OR rating IS NOT NULL)
          AND (:minYear IS NULL OR year >= :minYear)
          AND (:hasQuery = 0 OR rowid IN (
                SELECT rowid FROM movie_fts WHERE movie_fts MATCH :ftsQuery
              ))
        ORDER BY
          CASE WHEN :sort = 'RATING' THEN rating END DESC,
          CASE WHEN :sort = 'YEAR'   THEN year   END DESC,
          CASE WHEN :sort = 'ADDED'  THEN addedAt END DESC,
          sortName ASC
    """)
    fun pageMovies(
        portalId: Long,
        categoryId: String?,
        excludeAdult: Int,
        ratedOnly: Int,
        minYear: Int?,
        hasQuery: Int,
        ftsQuery: String,
        sort: String
    ): PagingSource<Int, MovieIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<MovieIndexEntity>)

    @Query("SELECT COUNT(*) FROM movie_index WHERE portalId = :portalId")
    fun observeCount(portalId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM movie_index WHERE portalId = :portalId")
    suspend fun count(portalId: Long): Int

    @Query("SELECT itemId FROM movie_index WHERE portalId = :portalId AND itemId IN (:ids)")
    suspend fun existingIds(portalId: Long, ids: List<String>): List<String>

    @Query("DELETE FROM movie_index WHERE portalId = :portalId AND syncEpoch < :syncEpoch")
    suspend fun prune(portalId: Long, syncEpoch: Long)

    @Query("DELETE FROM movie_index WHERE portalId = :portalId")
    suspend fun clear(portalId: Long)

    @Query("SELECT DISTINCT categoryId FROM movie_index WHERE portalId = :portalId")
    suspend fun distinctCategories(portalId: Long): List<String>
}
```

**Notes binding on all implementers:**
- `LIMIT/OFFSET` are intentionally **absent** — Room's Paging integration injects them. Do not add them.
- ORDER BY relies on SQLite's "NULLs sort lowest" rule: under `DESC`, `rating IS NULL` lands **LAST**. This satisfies decision 9. Do not add `IS NULL` guards.
- `sort` bind values are the literal strings `'RATING'`, `'YEAR'`, `'ADDED'`, `'NAME'` (NAME falls through to the `sortName ASC` tiebreaker).
- `excludeAdult`, `ratedOnly`, `hasQuery` are Int flags (0/1) because Room/SQLite has no Boolean bind in raw SQL comparisons here.
- `ftsQuery` must be a valid FTS MATCH string when `hasQuery = 1`; when `hasQuery = 0` it is ignored — pass `""`.

### 4.2 `SyncStateDao`

```kotlin
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM media_sync_state WHERE kind = :kind")
    fun observe(kind: String): Flow<SyncStateEntity?>

    @Query("SELECT * FROM media_sync_state WHERE kind = :kind")
    suspend fun get(kind: String): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
```

Engine does read-modify-write via `get` + `upsert` (whole-row REPLACE). No partial-update queries.

### 4.3 `FtsQuery.build` (FINAL spec)

```kotlin
object FtsQuery {
    /** "amelie jol" -> "amelie* jol*"; blank -> "". Sanitizes FTS metacharacters. */
    fun build(raw: String): String {
        val tokens = raw.trim().lowercase()
            .replace(Regex("[\"*^():]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }   // implicit AND, prefix on every token
    }
}
```

---

## 5. Database (Phase: Storage) — `MediaIndexDatabase.kt`

```kotlin
@Database(
    entities = [
        MovieIndexEntity::class,
        SeriesIndexEntity::class,
        MovieFtsEntity::class,
        SeriesFtsEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MediaIndexDatabase : RoomDatabase() {
    abstract fun movieIndexDao(): MovieIndexDao
    abstract fun seriesIndexDao(): SeriesIndexDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        fun create(context: Context): MediaIndexDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MediaIndexDatabase::class.java,
                "media_index.db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
```

Separate DB file `media_index.db`. No migrations array (rebuildable cache). `KnightDatabase`/`knightstalker.db` are untouched.

---

## 6. Normalization + mapper (Phase: Storage) — `MediaIndexMapper.kt`

Signatures are FINAL. Implement exactly these; unit-testable.

```kotlin
object MediaIndexMapper {

    /** rating_imdb -> rating_kinopoisk -> rate; "N/A"/""/"0"/non-numeric -> null;
     *  accepts comma decimals; clamps 0..10. */
    fun parseRating(imdb: String, kinopoisk: String, rate: String): Double?

    /** Leading 4 digits of `year` ("2022" or "2022-12-14") -> Int, else null. */
    fun parseYear(year: String): Int?

    /** "yyyy-MM-dd HH:mm:ss" -> epoch millis (portal-local tz); fallback 0L on parse fail. */
    fun parseAddedAt(added: String): Long

    /** `time` (minutes string) -> Int, else null. */
    fun parseDurationMin(time: String): Int?

    /** categoryId in adultCategoryIds OR name contains "|xXx"/"xXx|" (ci) OR censored == 1. */
    fun computeIsAdult(categoryId: String, name: String, censored: Int, adultCategoryIds: Set<String>): Boolean

    /** Strip leading |..| markers, strip accents (NFD + drop combining marks), lowercase, trim. */
    fun normalizeSortName(name: String): String

    /** Resolve adult category ids from a category list: title contains "ADULT" or "XXX" (ci). */
    fun adultCategoryIds(categories: List<VodCategoryData>): Set<String>

    fun VodData.toMovieEntity(portalId: Long, syncEpoch: Long, adultCategoryIds: Set<String>): MovieIndexEntity
    fun VodData.toSeriesEntity(portalId: Long, syncEpoch: Long, adultCategoryIds: Set<String>): SeriesIndexEntity
}
```

Mapper field wiring (both kinds identical, differ only in target class):

```
itemId      = id
name        = name
sortName    = normalizeSortName(name)
cmd         = cmd
categoryId  = categoryId
rating      = parseRating(ratingImdb, ratingKinopoisk, rate)
year        = parseYear(year)
addedAt     = parseAddedAt(added)
genresCsv   = genreStr
durationMin = parseDurationMin(time)
posterUrl   = screenshotUri
isAdult     = computeIsAdult(categoryId, name, censored, adultCategoryIds)
tmdbId      = tmdbId.ifEmpty { null }
actors      = actors
director    = director
syncEpoch   = syncEpoch
portalId    = portalId
```

`parseRating` detail: pick first of [imdb, kinopoisk, rate] that is non-blank, `!= "N/A"` (ci), replace `,`→`.`, `toDoubleOrNull()`, reject `<= 0.0`, `coerceIn(0.0, 10.0)`. Return null if none qualify.

---

## 7. Sync engine (Phase: Sync)

### 7.1 `MediaKind.kt`

```kotlin
enum class MediaKind(val key: String, val workName: String) {
    MOVIE("movie", "sync-movie"),
    SERIES("series", "sync-series")
}
```

### 7.2 `SyncConstants.kt`

```kotlin
object SyncConstants {
    const val PAGE_SIZE = 14            // portal max_page_items
    const val CRAWL_CONCURRENCY = 8
    const val UPSERT_BATCH = 500        // rows per Room transaction
    const val DELTA_MAX_PAGES = 40
    const val DELTA_THROTTLE_MS = 15L * 60_000L          // 15 min
    const val FULL_RECONCILE_MS = 7L * 24L * 3_600_000L  // 7 days
}
```

### 7.3 `SyncEngine.kt`

```kotlin
class SyncEngine(
    private val context: Context,
    private val api: StalkerApi,
    private val authManager: StalkerAuthManager,
    private val movieDao: MovieIndexDao,
    private val seriesDao: SeriesIndexDao,
    private val syncStateDao: SyncStateDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** UI-facing status stream (decision: sync-status flow). */
    fun status(kind: MediaKind): Flow<SyncStateEntity?> = syncStateDao.observe(kind.key)

    /** Decide + dispatch per §7 state machine. Suspend; safe to call on every tab open. */
    suspend fun ensureFresh(kind: MediaKind)

    /** Heavy resumable crawl. Called from the WorkManager worker. */
    suspend fun fullCrawl(kind: MediaKind)

    /** Lightweight incremental. Runs in `scope` (engine coroutine), not WorkManager. */
    suspend fun deltaSync(kind: MediaKind)
}
```

**`ensureFresh(kind)` logic (decisions 4,5,6,7):**
```
portal = authManager.currentPortal ?: return        // degrade gracefully, no crash
st = syncStateDao.get(kind.key)
when {
  st == null || st.lastFullSyncAt == 0L           -> enqueueFullCrawlWork(kind)   // WorkManager unique
  now - st.lastFullSyncAt > FULL_RECONCILE_MS     -> enqueueFullCrawlWork(kind)   // reconcile
  now - st.lastDeltaSyncAt < DELTA_THROTTLE_MS    -> return                       // throttled no-op
  else                                            -> scope.launch { deltaSync(kind) }
}
```
`enqueueFullCrawlWork`:
```
val req = OneTimeWorkRequestBuilder<MovieSyncWorker | SeriesSyncWorker>()
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)   // best-effort
    .build()
WorkManager.getInstance(context)
    .enqueueUniqueWork(kind.workName, ExistingWorkPolicy.KEEP, req)
```

**`fullCrawl(kind)` (decision 4):** uses raw `api.getVodList`/`api.getSeriesList` with `categoryId = null` (engine passes `"*"` semantics; api drops the param when null/`*`), `sortBy = "added"`, paging `p` from `state.crawlCursorPage.takeIf { it>0 } ?: 1`. First resolve `adultCategoryIds` via `api.getVodCategories`/`getSeriesCategories` + `MediaIndexMapper.adultCategoryIds(...)`. `stamp = System.currentTimeMillis()` is the syncEpoch. Dispatch up to `CRAWL_CONCURRENCY` pages concurrently through a `Semaphore(8)`; each page maps `VodData`→entity and `upsert` in batches of `UPSERT_BATCH`. Persist `crawlCursorPage` + `itemsIndexed` + `totalItemsRemote` as pages land; set `status="CRAWLING"`. Stop when a page returns empty data or `page*PAGE_SIZE >= total_items`. On finish: `dao.prune(portalId, stamp)`, set `lastFullSyncAt=now`, `crawlCursorPage=0`, `status="IDLE"`. On `401`/empty-js mid-crawl: re-handshake once via `api.handshake(portal.url)` (token is picked up by the existing interceptor through `authManager`), then continue from `crawlCursorPage`. On unrecoverable failure: `status="ERROR"`, `lastError=msg`; worker returns `Result.retry()`.

**`deltaSync(kind)` (decision 4):** `stamp=now`, page from 1; per page, `existing = dao.existingIds(portalId, pageItemIds)`, `new = items whose itemId ∉ existing`; `dao.upsert(all mapped rows)` (REPLACE refreshes changed metadata); if `new.isEmpty()` break; if `page >= DELTA_MAX_PAGES` break; else `page++`. No prune. Set `status="DELTA"` during, `lastDeltaSyncAt=now`, `status="IDLE"` after.

All `portalId = authManager.currentPortal!!.id`; every upsert/query is portal-keyed (decision 8).

### 7.4 Workers

```kotlin
class MovieSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val engine = (applicationContext as KnightStalkerApp).appModule.syncEngine
        return try { engine.fullCrawl(MediaKind.MOVIE); Result.success() }
        catch (e: Exception) { Result.retry() }
    }
}
```
`SeriesSyncWorker` identical with `MediaKind.SERIES`. Unique work names: `"sync-movie"`, `"sync-series"` (from `MediaKind.workName`). No manifest changes required (WorkManager default initializer); `FOREGROUND_SERVICE` permission already present.

---

## 8. Repository (Phase: Sync owns; Browse consumes) — `MediaIndexRepository.kt`

```kotlin
class MediaIndexRepository(
    private val movieDao: MovieIndexDao,
    private val seriesDao: SeriesIndexDao,
    private val syncStateDao: SyncStateDao
) {
    fun pageMovies(portalId: Long, categoryId: String?, excludeAdult: Int, ratedOnly: Int,
                   minYear: Int?, hasQuery: Int, ftsQuery: String, sort: String): PagingSource<Int, MovieIndexEntity> =
        movieDao.pageMovies(portalId, categoryId, excludeAdult, ratedOnly, minYear, hasQuery, ftsQuery, sort)

    fun pageSeries(portalId: Long, categoryId: String?, excludeAdult: Int, ratedOnly: Int,
                   minYear: Int?, hasQuery: Int, ftsQuery: String, sort: String): PagingSource<Int, SeriesIndexEntity> =
        seriesDao.pageSeries(portalId, categoryId, excludeAdult, ratedOnly, minYear, hasQuery, ftsQuery, sort)

    fun observeMovieCount(portalId: Long): Flow<Int> = movieDao.observeCount(portalId)
    fun observeSeriesCount(portalId: Long): Flow<Int> = seriesDao.observeCount(portalId)

    suspend fun clearPortal(portalId: Long) { movieDao.clear(portalId); seriesDao.clear(portalId) }
}
```

---

## 9. DI wiring (Phase: Wiring) — `AppModule.kt`

Add after the `database` property (the existing `KnightDatabase` stays). Insert these `by lazy` singletons (place after line 76, before `downloadRepository`):

```kotlin
    private val mediaIndexDatabase: MediaIndexDatabase by lazy {
        MediaIndexDatabase.create(context)
    }
    val movieIndexDao: MovieIndexDao by lazy { mediaIndexDatabase.movieIndexDao() }
    val seriesIndexDao: SeriesIndexDao by lazy { mediaIndexDatabase.seriesIndexDao() }
    val syncStateDao: SyncStateDao by lazy { mediaIndexDatabase.syncStateDao() }

    val mediaIndexRepository: MediaIndexRepository by lazy {
        MediaIndexRepository(movieIndexDao, seriesIndexDao, syncStateDao)
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(context, stalkerApi, authManager, movieIndexDao, seriesIndexDao, syncStateDao)
    }
```

No `init {}` block / no manual `WorkManager.initialize` — rely on the default WorkManager initializer. Workers reach `appModule.syncEngine` through `(applicationContext as KnightStalkerApp).appModule` (already exposed in `KnightStalkerApp`).

---

## 10. Browse rewrite (Phase: Browse) — `VodViewModel.kt`

### 10.1 Constructor + Factory — ADD two params (after `preferences`, before `mode`):

```kotlin
class VodViewModel(
    private val vodRepository: VodRepository,
    private val favoriteRepository: FavoriteRepository,
    private val downloadRepository: DownloadRepository,
    private val authManager: StalkerAuthManager,
    private val preferences: UserPreferences,
    private val mediaIndexRepository: MediaIndexRepository,   // NEW
    private val syncEngine: SyncEngine,                       // NEW
    private val mode: ContentMode = ContentMode.Vod
) : ViewModel()
```
Mirror the two new params in `class Factory(...)` and its `create()` call.

### 10.2 State — REPLACE `VodState`

```kotlin
data class VodState(
    val categories: List<VodCategory> = emptyList(),
    val items: Flow<PagingData<VodItem>> = emptyFlow(),   // was List<VodItem>
    val selectedCategory: String = "*",
    val error: String? = null,
    val selectedVod: VodItem? = null,
    val favoriteCategoryIds: Set<String> = emptySet(),
    val searchQuery: String = "",
    val sortBy: VodSort = VodSort.Rating,
    val syncStatus: String = "IDLE",     // IDLE | CRAWLING | DELTA | ERROR
    val itemsIndexed: Int = 0,
    val totalItemsRemote: Int = 0
)
```
**DELETE fields:** `isLoading`, `currentPage`, `totalItems`, `hasMore`, `rawLoaded`.

### 10.3 DELETE (client-side hacks now in SQL)
- `hasRating(...)`, `isAdult(...)`, `sortItems(...)`
- companion `MAX_RATED_RESULTS`, `MAX_SCAN_PAGES`
- `loadMore()`
- the entire accumulate/filter/`more` body inside `loadVodList`
- the favorite-mutation list rewrite inside `toggleFavorite` that maps `state.items` (items is a Flow now — drop that block; favorite toggle still calls the repo + updates `selectedVod`).

### 10.4 REPLACE `loadVodList` with a Pager builder

```kotlin
private fun rebuildPager() {
    val portal = authManager.currentPortal ?: return
    val cat = _state.value.selectedCategory
    if (cat == "favs") { loadFavoriteVod(); return }

    val q = _state.value.searchQuery.trim()
    val sortToken = when (_state.value.sortBy) {
        VodSort.Rating -> "RATING"
        VodSort.Year   -> "YEAR"
        VodSort.Added  -> "ADDED"
    }
    val categoryId = if (cat == "*") null else cat
    val excludeAdult = if (cat == "*") 1 else 0          // decision 13: "All" hides adult
    val ratedOnly    = if (_state.value.sortBy == VodSort.Rating) 1 else 0  // decision 13
    val hasQuery     = if (q.isNotBlank()) 1 else 0
    val ftsQuery     = if (hasQuery == 1) FtsQuery.build(q) else ""

    val flow = Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) {
        when (mode) {
            ContentMode.Vod -> mediaIndexRepository.pageMovies(
                portal.id, categoryId, excludeAdult, ratedOnly, null, hasQuery, ftsQuery, sortToken)
            ContentMode.Series -> mediaIndexRepository.pageSeries(
                portal.id, categoryId, excludeAdult, ratedOnly, null, hasQuery, ftsQuery, sortToken)
        }
    }.flow.map { data -> data.map { it.toVodItem() } }.cachedIn(viewModelScope)

    _state.update { it.copy(items = flow) }
}
```
Provide a local extension mapping the entity to the existing `VodItem`:
```kotlin
private fun MediaIndexRow.toVodItem() = VodItem(
    id = itemId, name = name, cmd = cmd, logo = posterUrl.ifEmpty { null },
    year = year?.toString(), genre = genresCsv.ifEmpty { null },
    rating = rating?.toString(), duration = durationMin?.toString(),
    director = director.ifEmpty { null }, actors = actors.ifEmpty { null },
    isFavorite = false
)
```
(`isFavorite=false` in the grid is acceptable for P3; the detail overlay still toggles correctly via id/cmd.)

### 10.5 `selectCategory`, `setSortBy`, `setSearchQuery`
Each updates the relevant `_state` field then calls `rebuildPager()` (no `items = emptyList()`, no page args). `setSearchQuery` keeps the 400 ms debounce then calls `rebuildPager()`.

### 10.6 `loadFavoriteVod` — keep favs working through the same Flow field
```kotlin
val list = favoriteRepository.getFavoriteVod(portal.id).first().map { /* -> VodItem */ }
_state.update { it.copy(items = flowOf(PagingData.from(list))) }
```

### 10.7 First-open auto-crawl + status (decisions 5,6) — in `init`
Inside the existing `connectionState` collector, when `CONNECTED`:
```kotlin
loadCategories()
rebuildPager()
syncEngine.ensureFresh(if (mode == ContentMode.Vod) MediaKind.MOVIE else MediaKind.SERIES)
launch { syncEngine.status(kind).collect { st ->
    _state.update { it.copy(
        syncStatus = st?.status ?: "IDLE",
        itemsIndexed = st?.itemsIndexed ?: 0,
        totalItemsRemote = st?.totalItemsRemote ?: 0) }
}}
```
`loadCategories()` keeps its current default-category landing (decision 13: `|FR| BOX OFFICE`); after landing it calls `rebuildPager()` instead of the old `loadVodList()`. The grid binds to Room from frame one regardless of index size.

### 10.8 Graceful degradation (decision 12)
If `mediaIndexRepository` count for the portal is 0 AND `authManager.currentPortal == null` (cannot crawl), `rebuildPager` returns early leaving `items = emptyFlow()`; `VodScreen` shows its empty state. Never crash. The `create_link` download/play path (`downloadVod`, `onPlayVod`) is unchanged.

---

## 11. `VodScreen.kt` (Phase: Browse)

- Replace `import ...grid.items` list binding with Paging: `state.items.collectAsLazyPagingItems()`.
- DELETE `reachedEnd` `derivedStateOf` + the `LaunchedEffect(reachedEnd, hasMore, isLoading){ loadMore() }` block (Paging auto-loads).
- Grid body:
```kotlin
val pagingItems = state.items.collectAsLazyPagingItems()
LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(160.dp), /* same modifiers */) {
    items(pagingItems.itemCount, key = pagingItems.itemKey { it.id }) { index ->
        pagingItems[index]?.let { vod -> VodCard(vod = vod, onClick = { viewModel.selectVod(vod) }) }
    }
}
```
- Empty/loading: drive off `pagingItems.loadState.refresh` (show `LoadingIndicator()` while `LoadState.Loading` and `itemCount == 0`; "No content found" when `NotLoading` and `itemCount == 0`).
- Indexing banner (decision 5): when `state.syncStatus == "CRAWLING"` (or `"DELTA"`), render a thin banner above the grid: `"Indexing ${state.itemsIndexed} / ${state.totalItemsRemote}…"`. Non-blocking; grid stays interactive.
- CategorySidebar wiring (sortOptions/search/select/favorites) stays exactly as-is.

---

## 12. `KnightNavGraph.kt` (Phase: Wiring)

At both call sites (lines ~316 and ~333), extend the `VodViewModel.Factory(...)` arg list to pass the two new singletons in the contract order (before `ContentMode.*`):

```kotlin
VodViewModel.Factory(
    module.vodRepository, module.favoriteRepository, module.downloadRepository,
    module.authManager, module.userPreferences,
    module.mediaIndexRepository, module.syncEngine,        // NEW
    ContentMode.Vod /* or ContentMode.Series */
)
```
No other nav changes. `key = "series"` on the series ViewModel stays.

---

## 13. Query-param mapping table (decision 13 — single source of truth)

| UX control | Query param | Value |
|---|---|---|
| Category sidebar `*` ("All") | `categoryId` | `null` |
| Category sidebar specific id | `categoryId` | that id |
| "All" selected | `excludeAdult` | `1` (else `0`) |
| Sort = Review/Rating (default) | `ratedOnly` | `1` (else `0`) |
| Sort = Review/Rating | `sort` | `"RATING"` |
| Sort = Release date | `sort` | `"YEAR"` |
| Sort = Date added | `sort` | `"ADDED"` |
| Search box non-empty | `hasQuery`,`ftsQuery` | `1`, `FtsQuery.build(text)` |
| (reserved) year filter | `minYear` | `null` for now |
| active portal | `portalId` | `authManager.currentPortal.id` |

Default state: `selectedCategory` lands on `|FR| BOX OFFICE` via `loadCategories()`; `sortBy = VodSort.Rating`. These are already the current defaults — preserved.

---

## 14. Phase ordering & gates

1. **Storage** — Gradle deps, DTO fields, entities, FTS, DAOs, DB, mapper, repository. Gate: build is green; mapper unit-test sanity (rating/year/added/isAdult/sortName).
2. **Sync** — SyncConstants, MediaKind, SyncEngine, two Workers. Gate: build green; full crawl populates rows (log row counts), delta stops in 1–3 pages.
3. **Browse** — VodViewModel + VodScreen rewrite, delete hacks. Gate: build green; grid renders from Room, sort/search/category/adult/rated all map to params.
4. **Wiring** — AppModule singletons + NavGraph factory args. (AppModule/Nav edits can land with Storage/Sync so the project keeps compiling; the two new Factory params must be added in the same change as 10.1.)

After EACH phase run the exact build command in the header. Definition of done for the whole contract: `assembleDebug` succeeds AND the Movies/Series browse path issues zero list network calls (only `create_link` on play/download).
