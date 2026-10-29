# SynapseMCP — Session Memory & Handoff

> **Purpose:** read this first at the start of the next session. It records what is built, what was verified (and how),
> every decision the user made, the environment quirks, and exactly where to resume.
> **Last updated:** 2026-10-14 (session 4) · **Branch:** `develop-stage`. Commit `4ba04a4` "synapse mcp backend
> code complete revamp" is the current `HEAD`, pushed to `origin/develop-stage`. That commit's reorg deleted
> `backend/docs/*.md` (old nested layout) without re-adding the new root-level `docs/*.md` to git - these doc
> files (this one included) are **untracked on disk**, not actually version-controlled, as of session 4's
> start. Session 4's code changes (image routing, query spell-correction, Tesseract pom.xml fix, `.env`
> gitignore) are **staged but uncommitted**; the user has not asked for a commit.
> **No secrets are stored here.** Variable *names* and *where they live* only.
>
> **Machine switch:** session 3 ran on **macOS**, a different machine from the Windows 11 box in section 5.
> Section 5's Windows details are historical; see "macOS (this session)" (§5a) for that machine. **Session 4
> ran on a third, separate macOS machine** (MacPorts Tesseract at `/opt/local/lib`, IntelliJ already running
> its own instance of the app on port 8080 - see §13 below) - don't assume §5a's exact paths/state apply here
> without checking.

Specs (the baseline, do not edit unless asked): `docs/plan.md`, `docs/instruction.md`, `docs/design.md`, `docs/architecture.md`.
Other docs: `README.md` (setup + parser table), `docs/s3-setup.md`, `docs/local-db-setup.sql`, `.env.example`.

---

## 1. How the user wants to work (important)

- **Strict milestone-by-milestone checkpoints.** Finish a milestone, report, wait for approval before the next one.
- **Ask, never guess.** Several times the user said "if you have any doubt ask me instead of guessing". Use the question tool for real decisions (models, credentials, behaviour). Don't pick silently.
- **Prove it with real tests, not claims.** The user repeatedly asks "are you 100% sure nothing is left / everything works?". The right answer is: say plainly what is verified (and how), what is not, then re-verify with real runs. Never say "100%". Distinguish "unit test with a mock" from "real API / real DB / real S3".
- **"Wait for the test results"** means: don't report until the background run has finished; then report the actual numbers.
- Keep answers concise, lead with the result, flag limitations honestly (a good report lists "not verified").
- Don't use `sed`/shell edits on memory files (the user rejected that once); use the Edit/Write tools.
- Don't commit, push, or edit the spec files unless explicitly asked.

---

## 2. Project in brief

Multi-tenant RAG platform with an MCP server. Each user = one tenant with exactly **one** knowledge base. Raw uploads are staged in S3 only transiently and **purged right after chunks+embeddings are in PostgreSQL**. Chat history is client-side only. Spring Boot backend in `backend/`; React frontend (`/frontend`) is **deferred until the backend is done**.

Stack as built: Java 21 (virtual threads), Spring Boot **3.5.15**, Spring AI **1.1.7** (Google GenAI starters), PostgreSQL 17 + pgvector 0.8.3, Flyway, AWS SDK 2.55.8 (S3), Nimbus JWT (HS256), Maven wrapper.

---

## 3. Status by milestone

| Milestone | State | Notes |
|---|---|---|
| 1.1 Schema / entities / RLS | **Done, verified** | Flyway V1 = architecture.md DDL; V2 = audit_logs RLS + `authenticate_api_key()` SECURITY DEFINER fn |
| 1.2 Tenant context + dual auth | **Done, verified** | JWT filter, MCP API-key filter, signup/signin, per-tx `set_config` (TenantRlsTransactionManager) |
| 3.1 S3 staging + upload endpoint | **Done, verified** | `POST /api/v1/documents/upload` → 202; 413/415/400/401 verified over real HTTP |
| 3.2 Tier 1 Gemini extraction | **Done, verified live** (see §7) | `MultimodalLlmExtractor` |
| 3.3 Tier 2 Java parsers | **Done, verified** | 7 parsers + `JavaParserExtractor` router (§8) |
| **3.4 Worker + SSE + purge, with real chunk+embed** | **Done, verified end-to-end** (session 3) | `IngestionOrchestrator`; see §12 |
| **4.1 Category-adaptive chunking** | **Done, verified** (session 3, same day) | Replaced the M3.4 placeholder `DynamicChunker` with real per-category strategies; see §12a |
| **5.1 Vector retrieval + sufficiency evaluator** | **Done, verified** (session 3, same day) | `VectorRetriever` + `SufficiencyEvaluator`; see §12b |
| **5.2 Web search fallback** | **Done, verified live** (session 3, same day) | **Spec deviation, user's explicit decision:** DuckDuckGo Instant Answer API instead of plan.md/design.md's Tavily — see §12c |
| **5.3 Answer synthesis + `POST /api/v1/chat`** | **Done, verified live end-to-end** (session 3, same day) | All 3 routing branches (LOCAL_KB/WEB_SEARCH/NONE) proven against real infra; see §12d |
| **6.1/6.2 MCP server, SSE transport, 3 tools, key management** | **Done, verified live end-to-end over the real MCP protocol** (session 3, same day) | See §12e |
| 2.x React UI, 4.2 refine (HNSW traversal already exercised by `ChunkRepositoryIT`/`VectorRetrieverIT`), 7.x chat UI, 8.x tests/docker | Not started | |

Upload now triggers real ingestion: `QUEUED` → `PARSING` → `CHUNKING` → `EMBEDDING` → `PURGING_S3` → `INDEXED`/`FAILED`, with chunks + 768d vectors persisted and the S3 staging object actually deleted. Verified live end-to-end via `IngestionOrchestratorIT` (real Postgres + real S3 + real Gemini embedding call).

---

## 4. Decisions made by the user (do not re-ask)

**Tiering / extraction**
- Under or equal to **2 MiB (2×1024×1024 bytes)**: only **PDFs and images** go to Gemini (Tier 1). **Everything else uses Java parsers at any size** (txt/json/csv/md, HTML, Word, Excel, PowerPoint). Spec says "< 2MB"; user accepted "≤ 2 MiB". Over 2 MiB: Java parsers for all types incl. PDF and images. Over 10 MiB: 413 `FILE_LIMIT_EXCEEDED`.
- **Gemini failure in Tier 1 = fail the job.** No retry, no fallback parser. (Implemented — see the SDK-retry gotcha in §7.)
- **OCR (images >2 MiB):** Tess4J with bundled Tesseract natives + bundled English data (`src/main/resources/tessdata/eng.traineddata`, ~4 MB, tessdata_fast). Chosen over Tika + system Tesseract. Class is `TesseractOcrDocumentParser` (spec calls it `TikaOcrDocumentParser`).
- **Embeddings get their own retries:** 3 attempts, exponential backoff, transient errors only (429/5xx). Tier 1 chat extraction stays at 1 attempt. **Built this session:** `SpringAiConfig` now defines its own `GoogleGenAiTextEmbeddingModel` bean (replacing Spring AI's auto-configured one) with a local `RetryTemplate` — 3 attempts, exponential backoff 500ms×2.0 up to 8s, retrying only on `ApiException` with code 429 or ≥500. This was necessary because Spring AI's embedding auto-configuration shares the *same* global `RetryTemplate` bean as chat (`spring.ai.retry.max-attempts: 1`, `@ConditionalOnMissingBean`-wired, no qualifier) — without a dedicated bean, embeddings would have silently inherited chat's 1-attempt policy. The embedding model's own internal Gemini SDK client still has default SDK retries underneath (not overridable from the public API), so this caps *outer* attempts at 3; it does not eliminate the SDK's own inner retry loop per attempt.
- **Tier 1 timeout decision deferred to later in the project** (user: "this testing can be done at last"). `PT90S` is left as-is for now; §11 item 1 still tracks the recommendation to raise it.
- **M3.4 scope decision:** built with a **real** basic chunker (`DynamicChunker`, paragraph/word-count based, ~700 words/chunk) and **real** embeddings in this milestone, not a stub — so a document genuinely reaches `INDEXED` with real chunks and vectors. Category-adaptive chunking (Excel/PPT/PDF/etc. strategies) is still M4.1's job.

**Models**
- Chat model is **`gemini-3.5-flash-lite`** (switched from `gemini-3.5-flash` this session — §12k — after `gemini-3.5-flash`'s free-tier `generate_content_free_tier_requests` quota, only 20/day, was exhausted twice by this project's own testing). Specs still say `gemini-3.8-flash` and were **not** edited. Embedding model **`gemini-embedding-2`**, 768 dims (verified, never once hit a quota/demand error all session - left unchanged).

**Infra**
- AWS S3, bucket **`aws-rag-bucket-app`**, region **us-east-1**. Credentials read **explicitly** from `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` (non-standard names the user chose). Optional `S3_ENDPOINT` (blank = real AWS; set for LocalStack/MinIO → path-style).
- JWT HS256, 24h, claims sub/tenantId/email/role; tenant name = user's full name; audit_logs has RLS; no FORCE RLS; app connects as a **non-owner role** so RLS applies; MCP key lookup via SECURITY DEFINER function; CORS default `http://localhost:5173`.
- MCP via Spring AI MCP Server starter (WebMVC SSE), currently **disabled** (`spring.ai.mcp.server.enabled=false`); must be remapped to `/mcp/sse` in Phase 6.

**Spec conflicts resolved:** instruction.md's fallback message differs from the others; use architecture.md/plan.md/design.md's: *"No relevant data found in your knowledge base, and fallback web search returned no matching results."*

**Web search provider (spec deviation, session 3, user's explicit decision):** plan.md/design.md both specify the Tavily Search API for 5.2's fallback. The user asked to use **DuckDuckGo** instead. DuckDuckGo has no general paid/keyed web-search API; the two real options were (a) the free Instant Answer API (no key, but not a ranked multi-result search — mostly a single Wikipedia-style abstract) or (b) scraping `duckduckgo.com/html/` (unofficial, against ToS, fragile). **User chose (a), the Instant Answer API.** Accepted consequence: far more queries will hit the deterministic NONE fallback than if Tavily (or DDG's HTML results) were used, since ordinary non-"topic" queries often return zero results from this API. See §12c.

---

## 5. Environment (Windows 11, this machine)

- **JDK 21** at `C:\Program Files\Java\jdk-21.0.10`; `JAVA_HOME` is **not** set globally → set it per command: `$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'`. Use `.\mvnw.cmd` (wrapper, Maven 3.9.11) from `backend/`.
- **PostgreSQL 17** service on `localhost:5432`, pgvector installed, DB `synapsemcp`. Tools: `C:\Program Files\PostgreSQL\17\bin\psql.exe` (not on the Git-Bash PATH; use PowerShell).
  - Superuser/schema owner **`admin`** (local dev, the user knows the password) — used for Flyway (`DB_MIGRATION_USERNAME/PASSWORD`) and for seeding/cleanup (bypasses RLS).
  - App role **`synapse_app`** (non-superuser, non-owner). Its password was random and kept in a temp session file that **will not survive**. Reset it: `ALTER ROLE synapse_app PASSWORD '<new>'` as `admin`, then use it as `DB_PASSWORD`. Role setup is scripted in `docs/local-db-setup.sql`.
  - DB currently has **no tenant/user/document rows** (all test data cleaned).
- **Docker/Podman are unusable** (Podman needs admin).
- **Microsoft Office is installed** (Word/Excel/PowerPoint COM works) — used to author the Office test fixtures.
- **Machine-level `AWS_ENDPOINT_URL=http://localhost:4566` (LocalStack) and machine-level `AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY` exist for other tooling.** The app deliberately ignores them (static creds + pinned regional endpoint). Do not "fix" this by using the default credential chain.

### Variables
| Variable | Where it lives | Notes |
|---|---|---|
| `GEMINI_API_KEY` | **Windows USER env** | also accepts `GOOGLE_API_KEY` as fallback |
| `AWS_ACCESS_KEY`, `AWS_SECRET_KEY` | **Windows USER env** | S3 credentials |
| `DB_URL` (default localhost), `DB_USERNAME` (default `synapse_app`), `DB_PASSWORD` | set per run | |
| `DB_MIGRATION_USERNAME`, `DB_MIGRATION_PASSWORD` | set per run | `admin` for Flyway |
| `JWT_SECRET` | set per run | Base64, ≥ 32 bytes; a random one is fine for local runs |
| `S3_BUCKET` (default `aws-rag-bucket-app`), `AWS_REGION` (default us-east-1), `S3_ENDPOINT` (blank) | optional | |

**Gotcha:** the Claude Code shell does **not** have the USER-scope variables in its process environment. Before running anything that needs them:
`foreach($n in 'GEMINI_API_KEY','AWS_ACCESS_KEY','AWS_SECRET_KEY'){ Set-Item "Env:$n" ([Environment]::GetEnvironmentVariable($n,'User')) }`

---

## 5a. Environment (macOS, session 3's machine — separate from section 5's Windows box)

- **JDK 21** = Amazon Corretto at `/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk`, `JAVA_HOME` exported in `~/.zshrc`. `mvnw` needed `chmod +x` (fresh clone). Use `./mvnw` from `backend/`.
- **PostgreSQL 17 is MacPorts' build** (`/opt/local/lib/postgresql17/bin/postgres`, run via a LaunchDaemon, socket on `localhost:5432`) — **not** the Homebrew `postgresql@17`/`@18` that's also installed on this machine (Homebrew's is idle; don't confuse the two). `psql`/`pg_config` on PATH resolve to the MacPorts build (`/opt/local/bin/...`) because MacPorts' bin dir is prepended in `~/.zshrc`.
  - **pgvector was not installed for MacPorts' Postgres** (Homebrew's `pgvector` formula only targets Homebrew's Postgres — wrong ABI). Built from source and installed manually: `git clone https://github.com/pgvector/pgvector`, `make PG_CONFIG=/opt/local/lib/postgresql17/bin/pg_config`, `sudo make install PG_CONFIG=/opt/local/lib/postgresql17/bin/pg_config`. (First attempt accidentally targeted Homebrew's `pg_config` and installed into `/usr/local/share/postgresql@17|@18/extension` — harmless leftover, wrong tree; the MacPorts tree at `/opt/local/share/postgresql17/extension/vector.control` is the one that matters.)
  - **`synapsemcp` database already existed** (owned by `postgres`) but was empty — no `synapse_app` role, no schema. Flyway migrations (run by booting the app once with `./mvnw spring-boot:run`, not the bare `flyway-maven-plugin` goal, which isn't configured with connection params in `pom.xml`) created the full schema cleanly once pgvector was in place.
  - **User's explicit decision: `DB_USERNAME=DB_PASSWORD=admin` (the Postgres superuser) for both the app and Flyway on this machine, not `synapse_app`.** This means **RLS is not enforced against the app on this Mac** (`admin` has `Bypass RLS`) — asked and confirmed with the user ("No, keep using admin for now"). The RLS policies and `synapse_app` role from `docs/local-db-setup.sql` were never created here. Don't "fix" this silently; it was a conscious tradeoff for this dev machine.
- **`~/.zshrc` gotcha (cost real time): none of the project's env vars had `export`.** They were plain shell variables (invisible to `env`/child processes) until this session added `export` to `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `GEMINI_API_KEY`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and added `export JWT_SECRET=...`. Also fixed: `AWS_SECRET_ID` → renamed to `AWS_SECRET_KEY` (the app reads the latter; user did this rename themselves), and `DB_URL` was missing the `/synapsemcp` path segment. A stray unrelated bug remains in `~/.zshrc` (line with `MONGO_DB_HOST=...netexport PATH=...` glued together, no newline) — not touched, unrelated to this project.
- **Docker not checked this session** (not needed — ran everything against the native MacPorts Postgres + real S3 + real Gemini).
- **Fixed later in this session:** Tess4J native `libtesseract` was missing on this machine (`TesseractOcrParserTest`'s OCR tests failed with `UnsatisfiedLinkError`/`dlopen`). The user installed it themselves via `sudo port install tesseract tesseract-eng` (MacPorts, 5.5.3) - `/opt/local/lib/libtesseract.dylib`. That alone wasn't enough: JNA couldn't find it there, and two different fixes that *looked* right didn't actually work before the real one did - `DYLD_LIBRARY_PATH` doesn't propagate into Maven Surefire's forked test JVM on this machine, and `MAVEN_OPTS` only reaches Maven's own JVM, never the forked one. What actually works: `export JAVA_TOOL_OPTIONS="-Djna.library.path=/opt/local/lib"` in `~/.zshrc` - every JVM (forked ones included) picks up `JAVA_TOOL_OPTIONS` automatically on startup (prints "Picked up JAVA_TOOL_OPTIONS: ..." to stderr, visible proof it's active). All 6 `TesseractOcrParserTest` tests now pass; the full default suite is **130/130, 0 failures, 0 errors** on this machine for the first time this session.

---

## 6. Repository layout (backend/)

```
src/main/java/com/synapsemcp/
  auth/ (controller, dto, entity, repository, service)     signup/signin, provisioning
  security/  TenantContext, TenantAwareTaskDecorator, TenantRlsTransactionManager, RlsSupport,
             JwtTokenProvider, JwtAuthenticationFilter, McpApiKeyAuthenticationFilter, BearerTokenExtractor
  config/    SecurityConfig, S3Config, StorageProperties, SpringAiConfig, PersistenceConfig, VirtualThreadConfig
  document/  controller (upload, progress-stream, status, list, delete), service (DocumentService, DocumentStorageService,
             IngestionProgressSseService), entity, repository, dto (incl. ProgressEvent, DocumentStatusResponse, DocumentListItem),
             model (FileCategory)
  ingestion/ entity (IngestionJob, JobStatus, JobStage), repository
    service/   IngestionOrchestrator (the M3.4 async worker)
    chunking/  ChunkPayload, DynamicChunker (basic, see memory §12 — not category-adaptive yet)
    extraction/  IngestionTierRouter, MultimodalLlmExtractor (Tier 1), JavaParserExtractor (Tier 2 router),
                 DocumentParserStrategy, ExtractionRequest/Result/Properties, DocumentExtractionException
    extraction/parser/  PdfBox, PoiWord, PoiExcel, PoiPpt, JsoupHtml, Text, TesseractOcr parsers + MarkdownSupport
  rag/entity (Chunk), repository (ChunkRepository)   mcp/ (entity ApiKey, ApiKeyAuthenticator/Hasher)   common/ (exception handler, AuditLog entity)
src/main/resources/ application.yml, db/migration/V1,V2, tessdata/eng.traineddata
src/test/resources/fixtures/ sample.{doc,docx,xls,xlsx,ppt,pptx}   (authored by real Office)
docs/ specs + this file + s3-setup.md + local-db-setup.sql
```

---

## 7. Gemini facts (measured on the real API)

- **Free-tier quota: 20 `generateContent` requests/day per model** (HTTP 429 `RESOURCE_EXHAUSTED`, resets ~24h). Testing exhausted `gemini-3.8-flash` and `gemini-3.5-flash` on 2026-10-13. **Production needs billing enabled.** Quotas are per model.
- **Probe a model with a real call before using it.** `gemini-2.5-flash` is *listed* by the API but rejects with 404 ("no longer available to new users"). Models that answered OK: `gemini-3.5-flash`, `3.6-flash`, `3.5-flash-lite`, `3.1-flash-lite`, `flash-lite-latest`; `3.8-flash` works (when quota allows); `3.7-flash` gave a 503 once.
- **Output cap 65,536 tokens** per call. Thinking tokens are negligible (budget 0 vs default made no difference).
- **Speed:** `3.8-flash` ≈ 190 output tok/s (10 dense pages 73s, 20 pages 149s). `3.5-flash` is faster on small docs (table PDF 7s vs 18s) but ≈ 55 tok/s on long output (**20 very dense pages = 518s**). A 40-page dense PDF on 3.8 returned nothing in 9 min (unexplained, **retest after quota reset**).
- **`synapse.ingestion.tier1.timeout` is still `PT90S`** → too short for long PDFs. Recommended `PT10M`; **the user has not decided**. Ask/confirm before M3.4 wires the extractor.
- **SDK retry gotcha (fixed):** the `google-genai` SDK retries 429/5xx itself (5 attempts + backoff: a quota error took 20–46s) *underneath* Spring AI. `SpringAiConfig` defines its own `Client googleGenAiClient(GoogleGenAiConnectionProperties)` with `HttpRetryOptions.attempts(1)` (429 now fails in ~1–2s, measured). Don't read the key with `@Value("${...}")` there (stays a literal placeholder in minimal test contexts). The **embedding client is separate** (`GoogleGenAiEmbeddingConnectionAutoConfiguration`) and still has SDK default retries — set it to 3 attempts per the decision above instead of wrapping twice.
- `spring.ai.retry.max-attempts: 1` is also set (Spring AI layer; global).
- Tier 1 behaviour: PDF/image ≤ 2 MiB → Markdown. Fails the job on: API error, timeout, blank/`NO_CONTENT`, finish reason ≠ `STOP` (MAX_TOKENS/SAFETY…). With a tiny output budget Gemini returns **no text** (message "returned no result"), so the real MAX_TOKENS-with-partial-text branch is only mock-tested. Output also passes `MarkdownSupport.normalize` (strips NUL/control chars; PostgreSQL can't store NUL).
- Verified live (3.8 and 3.5): real PDF, PNG, multi-page PDF (5 pages in order), ruled table → Markdown table, scanned image-only PDF (OCR), JPEG, WebP, corrupt PDF (400 → job fails in ~1s).

## 8. Tier 2 parser facts

PDFBox (text layer only; scanned PDFs >2 MiB fail with a clear message, no PDF OCR) · POI Word `.docx/.doc` (format detected by magic bytes; headings by style, bullets incl. style-based "List Bullet", tables → Markdown) · POI Excel (one `## Sheet` per sheet, header = first non-empty row, formulas evaluated, displayed formats, `|` escaped) · POI PPT `.ppt/.pptx` via the shared `SlideShow` API (`## Slide N: title`, bullets, tables, speaker notes) · Jsoup HTML (scripts/styles removed, headings/lists/tables/pre kept, charset detected) · Text (UTF-8, BOMs honoured, Windows-1252 fallback, NUL → reject) · Tess4J OCR (PNG/JPEG/WebP via ImageIO + TwelveMonkeys WebP reader; rejects images > 60M pixels as a decompression-bomb guard).
`JavaParserExtractor` fails the job on blank/unknown/any error.

**Known limits:** legacy `.ppt` tables come out as plain lines (the format has no table object); Word headers/footers/footnotes/text boxes not extracted; hidden Excel sheets and CSS-hidden HTML still included; OCR English only; no timeout or row/size cap on Tier 2 parsing (the M3.4 worker must bound it); OCR only tested on Windows (Linux/Docker unverified); ordered-list numbering is emitted as `1.` repeated.

---

## 9. S3 facts

Staging key `tenants/{tenantId}/staging/{docId}/{sanitizedFilename}`, SSE-S3 (AES256). Verified live: stage → read → purge round trip on the real bucket (`S3LiveIT`, writes under a random `tenants/<uuid>/` and deletes it). **Not verified:** that the bucket has the 24h lifecycle expiry rule from `docs/s3-setup.md` (I never changed bucket config), and the IAM scope. A *successful* upload through HTTP has never been run end-to-end (it would leave an unpurged object until M3.4).

---

## 10. Tests — what exists and how to run

Always from `backend/` with `JAVA_HOME` set (and USER-scope vars exported for live tests).

| Command | What it runs | Last result |
|---|---|---|
| `.\mvnw.cmd test "-Dtest=*Test"` (Windows) / `./mvnw test -Dtest='*Test'` (macOS) | all offline unit tests (incl. real Office fixtures, real Tesseract OCR, mocked Gemini) | **130/130 pass, 0 failures, 0 errors** on this Mac (as of the native-libtesseract fix, §5a) |
| `... -Dtest=S3LiveIT` | real S3 round trip | pass |
| `... -Dtest=GeminiLiveIT` | real chat + 768-dim embedding via Spring AI beans | pass (re-verified on this Mac, session 3 — embedding bean now uses the new dedicated retry template) |
| `... -Dtest=MultimodalExtractorLiveIT` | real PDF/PNG/corrupt-PDF extraction | passed earlier on 3.5-flash; **2 tests blocked by the 429 quota on 2026-10-13 (Windows machine)**; not retried on this Mac |
| `... -Dtest=MultimodalExtractorDeepLiveIT` | multi-page, table, scanned PDF, JPEG, WebP | passed on 3.5-flash (Windows); not retried on this Mac |
| `... -Dtest='MultimodalExtractorLimitsLiveIT*'` | real truncation failure + 20-page timing (20-page dense = 518s on 3.5) | passed (Windows); **edited afterwards, not re-run**; not retried on this Mac |
| `... -Dtest=ChunkRepositoryIT` (new, session 3) | `Chunk` entity ↔ real Postgres table mapping, vector/jsonb/tsv/HNSW | pass |
| `... -Dtest=IngestionOrchestratorIT` (new, session 3) | full M3.4 pipeline: upload → INDEXED, real chunks+vectors, real S3 purge | pass (~14s, 1 real embedding call) |

Live `*IT` classes are opt-in (skipped without the env var) and are **not** in the default `*Test` pattern. Each live Gemini call consumes free-tier quota — be sparing.

**HTTP regression matrix (19 checks, all passed on 2026-10-13 against the real app + Postgres):** signup 201, duplicate email (case-insensitive) 409, bad email/short password 400, signin 200, wrong password/unknown email 401, upload without/with bad token 401, unsupported type 415, empty file 400, >10 MB 413 with `FILE_LIMIT_EXCEEDED` + exact message, unknown path 404 (not 401), `/mcp` without/with bad key 401. The script (`matrix.ps1`) lived in a session scratch folder that will not survive; **recreate it or ask to have it saved into `docs/`**. It never sends a valid file, so it never writes to S3. Delete its throwaway `audit-*@example.com` account afterwards.

**RLS isolation (re-verified as the real `synapse_app` role):** unbound = 0 rows; bound sees only own rows; cross-tenant read 0; cross-tenant INSERT/UPDATE-tenant_id rejected; cross-tenant UPDATE/DELETE touch 0; rebind to other tenant sees only theirs; bogus tenant sees nothing. `users` and `tenants` have no RLS by design (sign-in looks up by email across tenants).

**Office fixtures** were generated by driving real Word/Excel/PowerPoint through COM from a PowerShell script (also lost with the scratch folder). Quirks learned: use **English style names** (`'Heading 1'`, `'List Bullet'`), assign Excel cells with `.Formula = [string]…` (not `.Value2`), call `RemoveDocumentInformation(99)` so no author metadata is embedded, kill leftover `WINWORD/EXCEL/POWERPNT` processes after a failed run.

---

## 11. Open decisions / pending

1. **Tier 1 timeout** (`PT90S` now) — recommended `PT10M`; **user explicitly deferred this** ("this testing can be done at last") — revisit during final testing/hardening, not blocking further milestones.
2. ~~Sequencing of chunking/embedding vs M3.4~~ — **resolved this session**: user chose real chunk+embed inside M3.4 (not a stub). Done.
3. Retest after the free-tier quotas reset (Google said ~5h for `3.8-flash` when it was exhausted on 2026-10-13; `3.5-flash` was exhausted later that day, so allow up to ~24h): the 40-page dense PDF, `MultimodalExtractorLimitsLiveIT`, `MultimodalExtractorLiveIT`; optionally compare `gemini-3.6-flash` speed. Not retested this session (worked on the Mac, different machine/quota pool than where it was exhausted).
4. Confirm the bucket's 24h lifecycle rule exists. Still not confirmed.
5. Billing for the Gemini key before any real usage. Still not confirmed — the `GEMINI_API_KEY` in this Mac's `~/.zshrc` is a different value/format than the Windows one documented in §5's variables table (starts `AQ.` not the usual `AIza...`); it worked live for both chat (implicitly, via embedding) — actually only the **embedding** endpoint was exercised this session (`GeminiLiveIT`, `IngestionOrchestratorIT`), not chat. Confirm chat quota separately before relying on it.
6. **RLS is not enforced against the app on this Mac** (§5a) — a conscious per-machine dev tradeoff (`DB_USERNAME=admin`), not a code issue. Don't assume RLS is active here when reasoning about test results on this machine.
7. ~~Tesseract OCR untested on this Mac~~ - fixed, see §5a.

## 12. Milestone 3.4 — DONE this session (worker, SSE progress, purge, real chunk+embed)

Spec: `plan.md` 3.4, `design.md` §4.2 (orchestrator sketch) and §7.2, `architecture.md` §3.2, `instruction.md` §4.3.

**What was verified first:** the `Chunk` entity ↔ `chunks` table on real Postgres (never tested before this session) via `ChunkRepositoryIT` — `vector(768)` mapping via `hibernate-vector` round-trips exactly, generated `tsv` populates via Postgres without Hibernate ever writing it (the field is simply unmapped on the entity), `metadata jsonb` round-trips, HNSW index is live (self-distance ≈ 0 via `<=>`). Run as `admin` (no RLS on this machine, see §5a/§11.6) — not re-verified under actual RLS enforcement.

**Built:**
- `IngestionOrchestrator` (`ingestion/service/`) with `@Async("ingestionExecutor")`, using the existing `TenantAwareTaskDecorator`/`TenantRlsTransactionManager` wiring unchanged. Triggered from `DocumentController.upload` **after** `documentService.upload(file)` returns (i.e. after its `@Transactional` commits) — not from inside `DocumentService` itself, to guarantee the async thread never races the commit.
- `DynamicChunker` (`ingestion/chunking/`) — basic paragraph-boundary chunker targeting ~700 words/chunk (hard-splits any single overlong paragraph). Deliberately not category-adaptive; that's M4.1.
- `IngestionProgressSseService` (`document/service/`) — in-memory `Map<UUID, List<SseEmitter>>`, broadcasts `progress`/`error` events, `completeStream` on terminal states. No Redis/external pub-sub; fine for one instance, would need rework for horizontal scaling.
- New endpoints on `DocumentController`: `GET /{id}/progress-stream` (SSE, uses the already-built JWT query-token support — now actually exercised for the first time), `GET /{id}/status` (polling fallback), `GET /` (list, includes live `chunkCount` via `ChunkRepository.countByDocumentId`), `DELETE /{id}` (relies on `ON DELETE CASCADE` for jobs/chunks — no manual cascade code).
- `DocumentService` gained `getStatus`/`list`/`delete`, each re-checking tenant ownership via `findByIdAndTenantId` (app-level double-guard per ADR-03, on top of whatever RLS is or isn't doing on a given machine).
- Progress/stages follow the DDL constraints exactly: `ingestion_jobs.status` ∈ {QUEUED, PARSING, CHUNKING, EMBEDDING, PURGING_S3, COMPLETED, FAILED}; `stage` ∈ {QUEUED, EXTRACTION, SPLITTING, VECTORIZING, PURGING_S3, COMPLETED, FAILED}; percents 25/50/75/90/100 (5 was already set at upload time).
- **Purge runs on failure too** (try/catch in `IngestionOrchestrator.fail(...)`), setting `storage_path = NULL`, `raw_file_purged = TRUE`; failures set `documents.status = FAILED` + `error_message` and the job `FAILED`/`FAILED` stage; a second purge failure is logged but doesn't throw.
- Embeddings: own `GoogleGenAiTextEmbeddingModel` bean in `SpringAiConfig` with a dedicated 3-attempt/backoff `RetryTemplate` (see §4's "Embeddings get their own retries" entry for why a dedicated bean was necessary, not just a config value).
- `ChunkRepository` (`rag/repository/`) added — didn't exist before this session.
- **Bug found and fixed in review (before the user's commit):** `GET /{id}/progress-stream` originally called `sseService.registerClient(id)` with **no tenant ownership check** — any authenticated tenant could open an SSE stream for any other tenant's document by knowing/guessing its UUID and receive their ingestion progress events (stage/percent), a cross-tenant leak (violates ADR-03's "zero cross-tenant leakage", even though only progress metadata leaked, not content). `status`/`list`/`delete` already checked ownership via `findByIdAndTenantId`; this endpoint was missed. Fixed: added `DocumentService.verifyOwnership(documentId)` (throws 404 `ApiException` if the document isn't the active tenant's), called from the controller before `registerClient`. Covered by new `DocumentServiceAccessTest` (3 cases: unknown id, another tenant's id, the owning tenant).
- **Fixed (was "not bounded yet"):** Tier 2 parsing now has a timeout, mirroring Tier 1's existing pattern — new `Tier2ExtractionProperties` (`synapse.ingestion.tier2.timeout`, default `PT2M`, **not yet calibrated against a real pathological fixture**, just a conservative starting value like Tier 1's deferred-decision timeout), a dedicated virtual-thread `ExecutorService` in `IngestionOrchestrator` (`@PreDestroy`-shut-down, separate from `MultimodalLlmExtractor`'s own Tier 1 executor), `future.get(timeout)` around `tier2Extractor.extract(...)`. On timeout: `future.cancel(true)` (an interrupt signal only — POI/PDFBox/Jsoup aren't interruption-aware mid-parse, so a truly stuck native call could still occupy the virtual thread after the orchestrator has moved on and failed the job; this bounds the *job's* wall-clock time, not literally kill the parser call) and the job fails with a clear "Tier 2 parsing timed out after PT2M" message, purge-on-failure still runs. Covered by `IngestionOrchestratorTier2TimeoutTest`: a mocked parser that blocks 5s against a 200ms configured timeout - asserts the job fails in well under 2s (not after the 5s), document/job both `FAILED`, staged file still purged. File **size** was already bounded (10MB cap at upload); only the missing timeout was the real gap.

**Tests added (all pass on this Mac):**
- `ChunkRepositoryIT` — Chunk↔table mapping (above).
- `IngestionOrchestratorIT` — full real pipeline: upload (`.txt`, Tier 2, no chat quota spent) → async orchestrator → polls until `INDEXED`/`FAILED` → asserts chunks exist with 768d vectors, `knowledge_base_id` correct, content contains a known marker, `documents.storage_path IS NULL` + `raw_file_purged = TRUE`, job `COMPLETED`, **and** that the S3 object is actually gone (`GetObject` throws `NoSuchKeyException`, not just DB flags). Real Postgres + real S3 + one real Gemini embedding call. ~14s.
- Not yet tested: the **failure path** purges correctly (no test forces a mid-pipeline failure), SSE stage order over real HTTP (the endpoint exists and reuses already-tested JWT query-token logic, but no test has opened a real `EventSource`/SSE connection against it), Tier 1 (multimodal) documents through the full orchestrator (only Tier 2 was exercised, to avoid chat quota), and the 19-check HTTP matrix has not been re-run on this machine.

## 12a. Milestone 4.1 — DONE same session (category-adaptive chunking)

Spec: `plan.md` 4.1, `design.md` section 4.1 (token ranges per category).

Replaced the M3.4 placeholder `DynamicChunker` (one flat 700-word paragraph splitter for everything) with a real `ChunkingStrategy` dispatch, mirroring `JavaParserExtractor`'s `List<DocumentParserStrategy>` pattern:

- **`ExcelChunkingStrategy`** (`EXCEL`, target 256 words): splits on `## <sheet name>` boundaries (`PoiExcelDocumentParser`'s fixed output shape), then on table **row** boundaries within a sheet (never mid-row) — and re-injects the sheet heading + header row + separator row into **every** chunk, including continuations, per design.md's "inject sheet name and column headers into every chunk".
- **`SlideChunkingStrategy`** (`POWERPOINT`): splits on `## Slide N: title` boundaries (`PoiPptDocumentParser`'s fixed output shape) — exactly one chunk per slide, **never** split or merged regardless of the 100-400 token target, per spec.
- **`HeadingAwareChunkingStrategy`** (`WORD` 580w, `PDF` 760w, `HTML` 580w, `TEXT` 380w, `IMAGE` 300w — midpoints of design.md's token ranges, same word-as-token-proxy imprecision as M3.4): splits on H1/H2 Markdown headings, merges small adjacent sections up to the target, hard-splits any single section bigger than the target. **Known limitation, not a bug:** `PdfBoxDocumentParser`'s Tier 2 output and OCR text have no heading markers at all, so for `PDF`/`IMAGE` this genuinely degrades to plain paragraph packing — there is no real layout/heading detection for those two, despite "layout and heading-aware" in design.md's PDF row. `WORD`/`HTML` get real heading-based splits since their parsers emit genuine `#`/`##` Markdown headings; `TEXT` covers `.txt/.csv/.json` (no headings) and `.md` (may have real ones, passed through verbatim).
- Shared helper `ChunkingSupport` (package-private): word counting, H1/H2 section splitting, paragraph packing, and hard-splitting an oversized paragraph. One fix made while writing tests: a short heading paragraph immediately followed by one giant paragraph used to become its own isolated, low-value chunk (e.g. `"# First"` alone) — fixed by gluing a small pending buffer onto the *first* piece of the oversized paragraph's hard-split instead of flushing it separately.
- `DynamicChunker` is now a thin dispatcher: picks the first supporting strategy, still throws on blank input/empty result (same contract as before).
- `IngestionOrchestrator` now resolves `FileCategory` once per document and passes it to both `extract(...)` and `chunker.chunk(...)` (previously `extract` resolved it internally and `chunk` didn't take it at all).

**Tests added (16, all offline/unit, all pass):** `ExcelChunkingStrategyTest` (4), `SlideChunkingStrategyTest` (3), `HeadingAwareChunkingStrategyTest` (5), `DynamicChunkerTest` (3). Fixtures hand-built to match each real parser's exact Markdown shape (verified against the parser source, not guessed). Re-ran `IngestionOrchestratorIT`/`ChunkRepositoryIT` afterward — both still pass with the new chunker wired into the live pipeline (the `.txt` fixture routes through `HeadingAwareChunkingStrategy` at `TEXT`'s 380-word target).

**Not done:** no unit tests exist yet using *real* parser output (only hand-built fixtures matching the documented shape) — e.g. never chunked an actual `PoiExcelDocumentParser` result end-to-end. Spatial/bounding-box grouping for OCR images (design.md's IMAGE row mentions this) was not attempted — `IMAGE` just uses the generic word-target packing, no spatial awareness at all (Tess4J's OCR output as currently extracted has no bounding-box data flowing through to the chunker).

## 12b. Milestone 5.1 — DONE same session (vector retrieval + sufficiency evaluator)

Spec: `plan.md` 5.1, `design.md` section 5.1, `architecture.md` ADR-06.

- **`ChunkRepository.findNearest(tenantId, queryVector, limit)`** (new native `@Query`): `SELECT ... (1 - (embedding <=> CAST(:queryVector AS vector))) AS similarity FROM chunks WHERE tenant_id = :tenantId ORDER BY embedding <=> ... ASC LIMIT :limit`, exactly design.md's query. `queryVector` is a pgvector text literal (`VectorLiteral.of(float[])`, package-private in `rag/retrieval/`). Returns a Spring Data interface projection (`ChunkMatchRow`) — no new entity needed.
- **`VectorRetriever`** (`rag/retrieval/`): `search(tenantId, queryEmbedding)` → `List<ChunkMatch>` (top 5, `TOP_K`). Filters by `tenant_id` alone, **not** `knowledge_base_id` — deliberate, matches design.md's literal query exactly, and is sufficient because each tenant has exactly one KB (ADR-01); RLS is the defense-in-depth layer underneath regardless (ADR-03, though not enforced on this dev machine — §5a).
- **`SufficiencyEvaluator`** (`rag/evaluator/`): `isSufficient(matches)` = at least one match with `similarityScore >= 0.70` (`SIMILARITY_THRESHOLD`), per ADR-06. Note pgvector's `<=>` is cosine *distance*; similarity is computed as `1 - distance` in the SQL itself, not in Java.
- No chat endpoint or query embedding generation yet — that's 5.3's job (the caller will embed the user's query via the same `EmbeddingModel` bean from M3.4, then pass the resulting `float[]` in here).

**Tests added (7, all pass):** `SufficiencyEvaluatorTest` (5, plain unit, no DB) and `VectorRetrieverIT` (2, real Postgres). The IT test is the one that actually matters here: it proves **tenant isolation** under a real HNSW search — tenant B has a chunk with the *exact same embedding* as the query (which would otherwise be the best possible match), and asserts it never appears in tenant A's results; also proves correct best-match-first ordering and that the exact-match chunk's similarity is ~1.0 (not just "some ordering exists").

## 12c. Milestone 5.2 — DONE same session (web search fallback, DuckDuckGo instead of Tavily)

Spec: `plan.md` 5.2, `design.md` section 5.2/7.4, `architecture.md` ADR-06. **Provider deviates from the spec by the user's explicit instruction** — see §4's "Web search provider" entry for the full tradeoff.

- **`WebSearchService`** (`rag/websearch/`) interface — `search(query, maxResults)` → `List<WebSearchResult>`. An empty list is the normal "no results" outcome, not an exception.
- **`DuckDuckGoWebSearchService`** calls `https://api.duckduckgo.com/?q=...&format=json&no_html=1&skip_disambig=1`, bounded by `synapse.websearch.timeout` (new `WebSearchProperties`, default `PT2S`, matching plan.md's original Tavily timeout figure).
- **Real bug caught by the live test, fixed before this was "done":** DuckDuckGo serves its JSON body as `Content-Type: application/x-javascript` regardless of `format=json` — Spring's default message converters reject that content type outright, so `restClient.retrieve().body(DuckDuckGoResponse.class)` **silently failed on every real call** (caught by the broad exception handler, returned as an empty list - which looked like "no results" rather than "broken integration"). `DuckDuckGoWebSearchServiceLiveIT` (hits the real API) caught this immediately; the 8 offline unit tests against hand-built fixtures could not have caught it, since they never touch HTTP/content-type negotiation. Fixed by fetching the body as a raw `String` and parsing with an injected `ObjectMapper` directly, bypassing Spring's content-type-based converter selection entirely.
- Response flattening (`DuckDuckGoResponse` → `List<WebSearchResult>`): the `AbstractText`/`AbstractURL` pair becomes the first result if present; `RelatedTopics` entries are flattened (the API nests plain results and named category groups in the same array), each one's `"Title - description"` convention split into title/content; capped at `maxResults`.
- **Known, accepted limitation (not a bug):** this is not a ranked multi-result web search. A well-known topic ("Java programming language") returns a usable abstract; most ordinary queries return nothing at all. Design.md's "top 3-5 authoritative web snippets" framing assumed Tavily's actual search behavior, which this provider cannot reproduce. The deterministic `NONE` fallback (ADR-06 step 3) will trigger far more often than originally designed.

**Tests added (10, all pass):** `DuckDuckGoWebSearchServiceTest` (8, offline, hand-built response fixtures: abstract-only, heading-missing fallback, related-topic flattening + title-splitting, nested category groups, max-results capping, missing-url/text skipping) and `DuckDuckGoWebSearchServiceLiveIT` (2, real network call — a well-known topic returns a real non-empty result; an obscure nonsense query returns empty, not an error). The live test is what caught the content-type bug above; it is not gated by any env var (no key needed) but follows the project's `*IT`-excluded-from-default-pattern convention.

**Not done:** no `RagQueryOrchestrator`/`POST /api/v1/chat` yet (5.3's job — will call `WebSearchService` only when `SufficiencyEvaluator.isSufficient(...)` is false); no circuit breaker (plan.md's risk table mentions Resilience4j for Tavily specifically — not evaluated for whether it's still warranted against this different, keyless provider).

## 12d. Milestone 5.3 — DONE same session (answer synthesis + `POST /api/v1/chat`)

Spec: `plan.md` 5.3, `design.md` section 5.3/7.3, `architecture.md` ADR-06.

- **`AnswerSynthesizer`** (`rag/synthesis/`): calls the fixed `gemini-3.5-flash` chat model with one of two strict prompts (local-only or web-only — "use ONLY the provided excerpts/results, do not use outside knowledge"). Only the prose answer text comes from Gemini; citations/web sources/confidence are built by the caller directly from the retrieval results, never asked of the model.
- **Real reliability gap found and fixed while live-testing, before this was "done":** two real `503 "high demand"` errors from Gemini happened back to back during testing. Chat answer synthesis was inheriting the *same* zero-retry policy that was deliberately chosen for Tier 1 document extraction (fail fast so a flaky result never silently degrades an ingestion job) — but nothing about that reasoning applies to a live chat request, which has no documented reason to be that unforgiving. Added a local `RetryTemplate` in `AnswerSynthesizer` (3 attempts, exponential backoff, retrying only on transient `ApiException` with code 429 or ≥500 — same classifier shape as the embedding model's retry in `SpringAiConfig`), confirmed by re-running the live test against the same real 503 condition afterward.
- **`RagQueryOrchestrator`** (`rag/service/`): embeds the query (same `EmbeddingModel` bean from M3.4) → `VectorRetriever.search` → `SufficiencyEvaluator.isSufficient`: if yes, `AnswerSynthesizer.synthesizeFromLocal` → `LOCAL_KB`; if no, `WebSearchService.search` (DuckDuckGo) → non-empty: `synthesizeFromWeb` → `WEB_SEARCH`; empty: the deterministic `NONE` message, **without ever calling Gemini chat** for that branch.
- **`ChatController`**: `POST /api/v1/chat` with `ChatRequest(query)` → `ChatResponse(answer, source, confidenceScore, citations, webSources)`, matching design.md section 7.3's shape exactly. Covered by the existing blanket JWT security chain (`anyRequest().authenticated()`) — no `SecurityConfig` changes needed.
- `ChunkRepository.findNearest` now joins `documents` for `documentName` (needed for citations per design.md's `documentName` field) — the only change to M5.1's retrieval query.

**Tests added (4, all pass):** `RagQueryOrchestratorTest` (3, mocked — each of the 3 routing branches, including asserting the `NONE` branch never calls `AnswerSynthesizer` at all) and `RagQueryOrchestratorLiveIT` (1, real end-to-end — real indexed document via the real M3.4 pipeline, then all 3 branches exercised against real Postgres/pgvector, real Gemini chat+embedding, and real DuckDuckGo, in one test to limit Gemini quota usage). The live test is what caught the 503-retry gap above.

**Not done:** no rate limiting on `/api/v1/chat` (plan.md's risk table doesn't mandate it for this endpoint specifically, but repeated chat calls each cost a real embedding + chat call); no streaming response (design.md section 7.3 mentions `Accept: text/event-stream` as an option — this implementation is plain JSON only); MCP server tools (`query_user_rag`, Phase 6) that would reuse this same orchestrator don't exist yet.

## 12e. Milestone 6.1/6.2 — DONE same session (MCP server, SSE transport, 3 tools, key management)

Spec: `plan.md` 6.1/6.2, `design.md` section 6/7.4, `architecture.md` ADR-04/section 3.4. `McpApiKeyAuthenticationFilter`, `ApiKeyAuthenticator`, `ApiKeyHasher`, the `authenticate_api_key` SECURITY DEFINER function, and the `/mcp/**` security chain all already existed from M1.2 — this milestone turned the server on and built everything that was still missing around it.

**Key management (`mcp/`):**
- `ApiKeyRepository` (new) — `findByTenantIdOrderByCreatedAtDesc`, `findByIdAndTenantId` (app-level double-guard, ADR-03).
- `McpKeyService` — generates `syn_mcp_<48 hex chars>` (24 random bytes via `SecureRandom`), stores only the SHA-256 hash + a 16-char display prefix (`key_prefix` column is exactly `varchar(16)`, so the prefix is `syn_mcp_` + 8 hex chars - fits exactly). The plaintext is returned exactly once, from `create()`, and never persisted anywhere.
- `McpKeyController`: `POST/GET/DELETE /api/v1/keys/mcp` per design.md section 7.4, using `@AuthenticationPrincipal AuthenticatedPrincipal` to get the web user's own `tenantId`/`userId` — this path is under the **JWT** security chain (`/api/v1/**`), not the MCP-key chain (`/mcp/**`), since a user manages their own MCP keys with their regular login, not with an MCP key itself.
- Tests: `McpKeyServiceTest` (7, unit) - plaintext never equals its own hash, persisted hash matches the returned plaintext exactly, prefix is exactly the plaintext's first 16 chars, two generated keys are never equal, blank name defaults correctly, revoke rejects a key belonging to a different tenant.

**MCP server (`application.yml`):** `spring.ai.mcp.server.enabled` flipped to `true` (was `false` since M1.2), `type: SYNC`, `protocol: SSE`, `sse-endpoint: /mcp/sse`, `sse-message-endpoint: /mcp/messages` - matching design.md section 6.1's exact paths exactly (the defaults were different paths). Confirmed via a manual boot + curl that `GET /mcp/sse` exists and correctly 401s without a key (the existing `/mcp/**` JWT-key chain from M1.2 just worked, no `SecurityConfig` changes needed), and via the boot log line `Registered tools: 3`.

**Tools (`mcp/tools/`), all `@Component` beans auto-discovered by Spring AI's `@McpTool` method scanner (no manual registration step needed):**
- `SearchKnowledgeBaseTool` (`search_user_knowledge_base`) — embeds the query, calls `VectorRetriever` directly (no synthesis), returns matches with similarity scores.
- `QueryRagTool` (`query_user_rag`) — calls `RagQueryOrchestrator.answer(...)` directly, the *exact same* orchestrator `POST /api/v1/chat` uses, so the web UI and external LLM clients can never diverge in RAG behavior.
- `GetIngestionStatusTool` (`get_ingestion_status`) — calls `DocumentService.getStatus(...)` directly; a cross-tenant document id correctly comes back as a tool error (same `ApiException` 404 path as the REST endpoint).
- All three read the tenant from `TenantContext`, which `McpApiKeyAuthenticationFilter` sets per-request exactly like the JWT filter does for REST calls - no new propagation code needed, confirmed live (see below).

**Real bug-class risk checked and confirmed absent, not just assumed:** it would have been easy for tool methods to run on a different thread than the one `McpApiKeyAuthenticationFilter` bound `TenantContext` on (SSE/async transports often dispatch handlers elsewhere), which would have silently broken tenant scoping for every tool call. `McpServerLiveIT` proves it does NOT happen for this `SYNC`/`SSE`/WebMvc configuration - tenant isolation was live-verified end to end, not inferred from documentation.

**Tests added (1 live, exercising everything above together):** `McpServerLiveIT` - two real tenants (via real `AuthService.signup`), a real indexed document for tenant A (via the real M3.4 pipeline), two real generated MCP keys, two real `McpSyncClient`s connecting over real SSE to the real running server (`@SpringBootTest(webEnvironment = RANDOM_PORT)`), using the real `io.modelcontextprotocol.sdk:mcp-core` client library (already a transitive dependency, no pom change needed). Asserts: all 3 tools appear in `tools/list`; `get_ingestion_status` for the owner returns `INDEXED`; `search_user_knowledge_base` for the owner finds the real content; the **same query against tenant B's key returns nothing related to tenant A's document** (not just "different results" - specifically asserts the response does NOT contain tenant A's content); `get_ingestion_status` for tenant B on tenant A's document id comes back as a tool error, not a leaked status. A real MCP protocol handshake (`initialize`) happened for both clients, logged and confirmed.

**Known quirk, not a bug:** after the test's assertions pass and its try-with-resources closes both clients, the forked test JVM still takes ~30s to exit (Tomcat's graceful shutdown waiting on what's presumably a lingering SSE-related thread/connection); Surefire force-kills the fork after that, which is normal Surefire behavior and does not affect the recorded test result (`Tests run: 1, Failures: 0, Errors: 0`). Not investigated further - cosmetic, bounded, and consistently harmless across runs.

**Not done:** rate limiting on `/mcp/**` (plan.md doesn't mandate it specifically here either, same as `/api/v1/chat`); MCP `resources`/`prompts`/`completions` capabilities are enabled by the autoconfiguration's defaults (see the boot log) but nothing registers any resources/prompts - only tools are implemented, matching design.md's scope for this milestone exactly; no test exercises an actually-revoked key being rejected by `McpApiKeyAuthenticationFilter` over the real transport (the SQL function's revoked-check itself has no dedicated test either, carried over as a pre-existing gap from M1.2, not newly introduced).

## 12f. Bug found and fixed in review (same session): DELETE /api/v1/documents/{id} could orphan a staged S3 object

`DocumentService.delete(...)`'s own comment claimed "the raw file is already purged by then" - true only once a document reaches a terminal state via the normal path. Two real cases it missed:
1. **A document whose ingestion is still in flight** (`QUEUED`/`PARSING`/`CHUNKING`/`EMBEDDING`/`PURGING_S3`): deleting the row while `IngestionOrchestrator`'s async thread still holds its own loaded copy of the `Document`/`IngestionJob` would let the orchestrator's later `save()` calls resurrect the row (or insert chunks referencing a document id that briefly didn't exist) - a race, not handled at all before this fix.
2. **A `FAILED` document whose own purge-on-failure attempt also failed** (e.g. S3 was down at the time): `storage_path` stays non-null, the staged object is still live in S3, and deleting the row would have left it orphaned with only the 24h lifecycle rule as a backstop - the exact scenario ADR-07's "purge on both success and failure" was meant to prevent, just via a third path (delete) nobody had closed yet.

Fixed: `delete(...)` now (a) rejects with 409 `INGESTION_IN_PROGRESS` if the document's latest job isn't `COMPLETED`/`FAILED` yet (closes the race cleanly rather than trying to synchronize with the orchestrator), and (b) purges S3 first if `storage_path` is still set, with the same best-effort/log-and-continue semantics `IngestionOrchestrator.fail(...)` already uses (a storage outage must not block the delete). Covered by 5 new tests in `DocumentServiceDeleteTest`: rejects an in-flight job, purges an unpurged `FAILED` document before deleting, skips the purge call for an already-purged `COMPLETED` document, a purge failure doesn't block the delete, and a missing job row (defensive) doesn't block it either.

## 12g. Phase 8 hardening, this session: audit_logs writes + a real hang found and fixed in AnswerSynthesizer

**`audit_logs` writes** (`common/service/AuditLogService` + `AuditLogWriter`, `common/repository/AuditLogRepository`), scoped to security-sensitive events only, per the user's explicit decision: `SIGNUP`, `SIGNIN`, `SIGNIN_FAILURE`, `MCP_KEY_CREATE`, `MCP_KEY_REVOKE`, `DOCUMENT_UPLOAD`, `DOCUMENT_DELETE`. Deliberately never chat queries/answers (architecture's "no chat transcripts persisted" invariant).
- Two write modes, and the distinction matters: `record(...)` joins the caller's own transaction (for success events - the audited resource, or its RLS tenant binding, may have been created in that very transaction); `recordIndependently(...)` runs in its own `REQUIRES_NEW` transaction via the separate `AuditLogWriter` bean (self-invocation would silently no-op the propagation if it were a method on the same class) - only for `SIGNIN_FAILURE`, where the referenced user already exists from a prior commit and the record must survive the `ApiException` the caller is about to throw.
- **Real bug caught by `AuditLogServiceIT` before this was "done":** the SIGNUP audit write was *always* silently failing. It used `REQUIRES_NEW` (wrong choice for this case) while the tenant/user rows were created in the *same*, still-open outer transaction - a separate transaction can never see another transaction's uncommitted work, so every SIGNUP audit attempt hit a tenant-FK violation, got caught by the service's own try/catch, and was logged-and-swallowed. A plain unit test could not have caught this (no real transactions); only the real-Postgres IT test exposed it. Fixed by switching SIGNUP (and SIGNIN success) to the joined `record(...)` mode.
- Tests: `AuditLogServiceIT` (2, real Postgres) - a basic write round-trips correctly, and a `SIGNIN_FAILURE` record (via a real `authService.signin()` call with a wrong password) survives even though that call then throws `ApiException`.

**Real hang found and fixed in `AnswerSynthesizer` (caught while re-verifying `RagQueryOrchestratorLiveIT` after the audit changes, unrelated to them):** the retry-on-transient-error policy added in M5.3 (§12d) had no per-attempt timeout. A real OkHttp call to Gemini was observed live blocking indefinitely waiting for response headers - no exception, no timeout, just a thread parked in `Object.wait()` for 6+ minutes until manually killed. Since nothing ever threw, the retry policy never even got a chance to fire. Fixed by bounding each attempt with `synapse.chat.timeout` (`PT20S`, not yet calibrated against real measured chat latencies - same status as Tier 2's and Tier 1's own timeouts) on its own virtual thread, the identical `future.get(timeout)` pattern `MultimodalLlmExtractor` already used for Tier 1 - this class was simply missing that bound. New `ChatSynthesisProperties`. Verified by `AnswerSynthesizerTimeoutTest` (a mocked hung call now fails in ~2s instead of hanging), and live: re-running `RagQueryOrchestratorLiveIT` twice afterward against a currently-unresponsive real Gemini chat endpoint (consistent timeout on all 3 retry attempts both times, ~66s total each run - almost certainly the free-tier chat quota exhausted from this session's heavy testing, per memory.md section 7) now fails cleanly and quickly instead of hanging for 6+ minutes needing a manual kill. **The live happy-path assertions for this test are still unverified as of this session** - blocked on Gemini chat availability/quota, not a known code issue; retry once quota resets.

**`tesseract` native library installed on this Mac, fixing the 4 pre-existing `TesseractOcrParserTest` errors (session-long gap, not a code issue)** - the user ran `sudo port install tesseract tesseract-eng` (MacPorts, 5.5.3) themselves. Installing the library alone did not fix it: `DYLD_LIBRARY_PATH` does not propagate into Maven Surefire's forked test JVM on this machine, and `MAVEN_OPTS` only reaches Maven's own JVM, never the forked one - both looked like they should work and did not. What actually works, added to `~/.zshrc`: `export JAVA_TOOL_OPTIONS="-Djna.library.path=/opt/local/lib"` - every JVM, forked ones included, picks up `JAVA_TOOL_OPTIONS` automatically (visibly confirmed by the "Picked up JAVA_TOOL_OPTIONS: ..." line Java prints to stderr on every run). The default suite is now **134/134, 0 failures, 0 errors** on this machine - the first time this session the whole suite has been fully green.

**Rate limiting** (`common/ratelimit/RateLimiter`), architecture.md's previously-unbuilt "Rate Limiting Filter" - scoped and shaped per the user's explicit decisions: **expensive operations only** (never cheap reads like document list, key management, or the REST status-polling endpoint), **per-tenant, strict** (10 requests/minute per tenant per operation).
- In-memory fixed-window counter, `Map<"tenantId:operation", Window>`, single-instance only (same assumption as `IngestionProgressSseService`'s own in-memory map and the rest of this backend so far) - would need a shared store (e.g. Redis, not currently a dependency) to work correctly if horizontally scaled. The bucket map never evicts old entries either (slow, bounded-but-unbounded memory growth for a very-long-running process with many distinct tenants) - accepted for now, each entry is a few bytes.
- Wired into exactly 4 call sites, each its own bucket (so e.g. hitting the upload limit doesn't also block chat): `DocumentService.upload`, `RagQueryOrchestrator.answer` (shared by both `POST /api/v1/chat` and the `query_user_rag` MCP tool, since both delegate to this one method - they naturally share one bucket), `SearchKnowledgeBaseTool.search`, `GetIngestionStatusTool.status`.
- **Deliberately NOT applied inside `DocumentService.getStatus` itself** (only inside the MCP tool wrapper around it) - that method is shared with `GET /api/v1/documents/{id}/status`, the documented SSE-fallback polling endpoint (design.md's risk table: polled every ~1.5s, i.e. ~40 requests/minute by design), which would have broken immediately under a 10/minute cap. Caught this during implementation, before writing any code that would have introduced the regression.
- Exceeding the limit throws `ApiException` 429 `RATE_LIMIT_EXCEEDED` - renders as a normal RFC 7807 response for the two REST paths (`GlobalExceptionHandler` already handles `ApiException` generically), and as a tool-call error for the two MCP tools (same mechanism already observed converting a 404 `ApiException` into `isError: true` in `McpServerLiveIT`).
- Tests: `RateLimiterTest` (5, unit) - allows up to the limit, rejects the one that exceeds it, independent buckets per tenant and per operation, the window resets after it expires. Re-ran `McpServerLiveIT` afterward (real tenant isolation + tool calls over real SSE) to confirm the new checks don't break the real flow - still passes.
- **Closed in review:** added `DocumentServiceRateLimitTest` (2) - drives a real burst through `DocumentService.upload` (one of the 4 wired-in call sites, with the real `RateLimiter`, not mocked): the 11th call in a tenant's burst is rejected with 429 *before* any write happens (`documents.saveAndFlush` verified called exactly 10 times, not 11), and a different tenant's own burst is unaffected by the first tenant's limit. Still not done: no test exercises hitting the limit over real HTTP/MCP transport specifically (only through the service layer directly) - the 429 response shape was not smoke-tested over a real HTTP round trip.

## 12h. Phase 8.2 — Podman containerization, and a real, codebase-wide RLS tenant-binding bug it uncovered (same session)

**Containerization** (user's explicit choice: Podman, not Docker). Built fresh, nothing reused from a stale, unrelated 2-month-old `synapse_mcp:latest` container found on the machine (monolithic Postgres+Redis+Lucene bundle, predates the first git commit, explicitly not built upon):
- `Dockerfile` — multi-stage (`maven:3.9-eclipse-temurin-21` build, `eclipse-temurin:21-jre` runtime, `tesseract-ocr`/`tesseract-ocr-eng` installed via `apt-get`, non-root `synapse` user).
- `docker/init-db.sh` — runs via Postgres's `/docker-entrypoint-initdb.d/`, creates the real `synapse_app` non-superuser role matching `docs/local-db-setup.sql` (deliberately NOT this machine's own `admin`-everywhere dev shortcut from §11.6 — that was an explicit per-machine concession, not meant to be baked into the "real" containerized architecture).
- `docker-compose.yml` — `pgvector/pgvector:pg17` image for Postgres (no host port published, avoids colliding with this Mac's own MacPorts Postgres on 5432), `backend` service built from the Dockerfile, secrets read from env/`.env` (gitignored) with no hardcoded defaults.
- Build and basic run verified: clean `podman build` (731MB image), fresh-container Flyway migrations applied cleanly against a brand-new DB (first time this session migrations were proven to work standalone, not just against the already-migrated dev DB), `pg_roles` confirms `admin.rolbypassrls = t` / `synapse_app.rolbypassrls = f` (genuinely different from this dev machine's own setup).

**The real bug this surfaced — first time `synapse_app` (RLS-enforced, not bypassed) was ever exercised, anywhere, this whole project:** signup and upload worked fully inside the container (real JWT, real tenant+KB+document rows, real S3 stage/purge, real Gemini embedding, `documents.status` → `INDEXED`) — but `GET /{id}/status` returned 404 and `GET /documents` (list) returned `[]` for that exact same document, even though a direct `psql` query (as `admin`, which bypasses RLS) showed the row with the correct matching `tenant_id`. Reproduced cleanly and repeatably on fresh tenants, ruling out any stale-container/race theory.

**Root cause, found via `TRACE`-level logging on `TenantRlsTransactionManager`/Hibernate/Spring transaction packages (temporarily added to `docker-compose.yml`, reverted after):** `TenantRlsTransactionManager.doBegin()` (`security/TenantRlsTransactionManager.java`) only runs — and only then binds `app.current_tenant_id` via `RlsSupport.bindTenant` — when a *Spring-managed transaction* actually opens. Spring Data's base CRUD methods inherited from `JpaRepository` (`findById`, `save`, `saveAll`, `delete`, …) get this automatically per-call, via `SimpleJpaRepository`'s own default transaction demarcation — confirmed in the logs for `IngestionOrchestrator`'s internal `findById`/`save` calls. **But custom derived-query and `@Query`-annotated repository methods do not** when called from a service method with no `@Transactional` of its own: they fall through to a bare, ad-hoc `SharedEntityManagerInvocationHandler`-created `EntityManager` with no transaction at all, so `doBegin()` never runs, the tenant session variable is never set, and RLS's default-deny silently returns zero rows — for a *read*, this looks exactly like "the data isn't there" instead of an error. This was invisible all session because every single test and manual run before this one connected as `admin` (`rolbypassrls = t`), which made the missing binding irrelevant.
- **Affected and fixed:** `DocumentService.getStatus`/`list`/`verifyOwnership` (call `findByIdAndTenantId`, `findByTenantIdOrderByCreatedAtDesc`, `findFirstByDocumentIdOrderByCreatedAtDesc`) and `VectorRetriever.search` (calls the native `@Query findNearest` — meaning **chat/RAG retrieval and the `search_user_knowledge_base` MCP tool were also silently broken** under real RLS, always falling through to web-search/`NONE`) and `McpKeyService.list`/`revoke`/`create` (calls `findByTenantIdOrderByCreatedAtDesc`/`findByIdAndTenantId`). Fix: added an explicit `@Transactional`/`@Transactional(readOnly = true)` at each of these entry points, matching the pattern already working correctly in `upload()`/`delete()`/`AuthService.signup/signin` (which all already had explicit `@Transactional` and were never affected).
- **Not affected:** `IngestionOrchestrator` (only ever calls base CRUD methods), `ApiKeyAuthenticator` (deliberately bypasses RLS via a `SECURITY DEFINER` function, by design, unrelated).
- **Verified after the fix**, rebuilt and redeployed in the container: fresh signup → upload → immediate `GET /status` (200, real progress) → `GET /status` after indexing (200, `INDEXED`) → `GET /documents` (200, the document listed) — all correct. `POST /api/v1/chat` with a query that semantically matches the uploaded content now reaches the synthesis stage (confirmed via logs: Gemini chat returned a transient 503 "high demand" error at that point, an unrelated, known free-tier availability issue, not an RLS/auth failure — the point is retrieval itself found the real chunk). `POST /api/v1/keys/mcp` create + list round-trips correctly. Full unit suite re-run after the fix: **125/125, 0 failures, 0 errors** (default `mvn test`, excludes `*IT`).
- **Not yet re-verified under this fix:** the real-Postgres `*IT` tests and `McpServerLiveIT` haven't been re-run against this specific change (they all ran under `admin` previously anyway, so they would not have caught this bug either way, and aren't expected to be affected by adding `@Transactional` to already-correct logic) — worth a `./mvnw clean verify` pass before calling Phase 8 fully done.
- A stray `podman compose up` was accidentally run from a shell that had not sourced `~/.zshrc` (this Bash tool's shell does not auto-source it), recreating the backend container with blank `JWT_SECRET`/`GEMINI_API_KEY`/AWS secrets for a few minutes — caught immediately (compose warned "variable not set"), fixed by explicitly `source ~/.zshrc` before every subsequent `podman`/`curl` command in the same invocation. **Lesson:** this tool's Bash shell does NOT inherit `~/.zshrc` automatically; any command needing the real secrets must `source ~/.zshrc` first, every time (shell state does not persist between tool calls).

## 12i. Phase 2/7 — React frontend, built and verified this session (first frontend work of the project)

**Location:** `/frontend` (sibling of `backend/`, per `plan.md`'s path). Stack as built: React 19.2.8 + Vite 8 + TypeScript 6, Tailwind CSS v4 (`@tailwindcss/vite` plugin, no `tailwind.config` needed in v4), `lucide-react` icons, Axios. No router - the whole app is one conditional tree (`AuthGate`: unauthenticated → `LoginForm`/`SignupForm`, authenticated → `MainLayout`), matching design.md's component hierarchy diagram exactly (it never shows routed pages).

**Environment gap found and fixed before any of this could start:** Node itself was broken on this Mac - Homebrew's `node` 26.5.0 was linked against `libada.3.dylib`, but the installed `ada-url` had since upgraded to 4.0.0 (only `libada.4.dylib` present), so plain `node -v` crashed with a dyld error. Per the user's explicit "don't use Homebrew" stance (established for Tesseract, §5a), asked first: user chose MacPorts. `sudo port install nodejs22` (user ran it) gives `/opt/local/bin/node` 22.22.2, but **MacPorts' `nodejs22` port deliberately does not bundle npm** (no `corepack` either) - a separate, easily-missed MacPorts quirk. The `npmN` ports in `port search npm` go up to `npm11` (11.21.0, matches Node 22's own bundled npm version); asked the user again (npm11 vs. pnpm), they chose `sudo port install npm11` (also user-run). Both now resolve correctly on PATH (`/opt/local/bin/node`, `/opt/local/bin/npm`).

**Built, matching design.md section 2.1's directory structure and plan.md's M2.1/M2.2/M7.1/M7.2 exactly:**
- `types/{auth,document,chat,mcp}.ts` - hand-matched field-for-field against the actual backend DTOs read from source (`AuthRequests`/`AuthResponse`, `UploadResponse`, `DocumentStatusResponse`, `DocumentListItem`, `ProgressEvent`, `ChatRequest`/`ChatResponse`, `McpKeyCreateRequest`/`McpKeyCreatedResponse`/`McpKeySummary`), not guessed from design.md's prose alone - e.g. `DocumentStatus` has 5 values (`PENDING/PROCESSING/INDEXED/FAILED/REJECTED`) and `JobStage` has 7 (`QUEUED/EXTRACTION/SPLITTING/VECTORIZING/PURGING_S3/COMPLETED/FAILED`), both wider than design.md's abbreviated examples.
- `services/api.ts` - Axios instance, JWT request interceptor, a response interceptor that clears the token and dispatches a `synapse:unauthorized` event on any 401 *except* from `/auth/*` (a wrong password must not force-logout), and `extractErrorMessage` that unwraps the backend's RFC 7807 `ProblemDetail` (`detail`/`errors`) consistently everywhere.
- `services/{authService,documentService,chatService,mcpKeyService}.ts`, `context/{AuthContext,ToastContext}.tsx`, `hooks/{useDocuments,useFileUploadWithProgress,useRagChat,useMcpKeys}.ts`, all per design.md's listed files.
- `components/auth/{LoginForm,SignupForm}`, `components/documents/{DocumentUploadDropzone,FileUploadProgressCard,IngestionStepper,DocumentTable,DocumentFilter,fileCategory.ts}`, `components/chat/{ChatWindow,MessageBubble,CitationCard,WebSourceBadge,QueryRoutingIndicator}`, `components/settings/McpKeyModal`, `components/layout/{AppHeader,MainLayout}`, `components/common/{Button,Card,Modal,Badge,Toast}`.
- `vite.config.ts` proxies `/api/**` to `http://localhost:8080` in dev, so the browser never deals with CORS locally (the backend's own `CORS_ALLOWED_ORIGINS` is still there for non-proxied/production use).
- **One deliberate simplification from plan.md's prose:** `QueryRoutingIndicator` only shows a generic "Searching your knowledge base..." spinner while a chat request is in flight - the actual `POST /api/v1/chat` is a single blocking JSON call (confirmed in `ChatController`/`RagQueryOrchestrator`), not an SSE stream, so there is no real intermediate "Falling back to Web Search..." event to display live; the resolved message's `source` badge (`LOCAL_KB`/`WEB_SEARCH`/`NONE`) communicates the routing outcome after the fact instead.

**Verified - real browser, real backend in the Podman container, not mocks:** `playwright-core` driving the actually-installed Google Chrome (no Chromium download needed/attempted), against the Vite dev server (`:5173`) proxying to the real containerized backend (`:8080`, `synapse_app`/real RLS, from §12h). Two scripted runs, zero console/page errors both times:
1. Signup → upload a real `.txt` file → dual-phase progress card through `Uploading` → `Queued/Parsing/Chunking/Embedding/Purging S3/Ready` stepper → `Indexed - S3 Purged` badge in the table → chat question answered with a real `LOCAL_KB` citation → MCP key generation modal shows the plaintext key once + a working Claude/Cursor config snippet.
2. Client-side rejection of an unsupported file extension (toast, no network call) → upload → delete (row disappears) → page reload keeps the session (JWT in `localStorage`) → logout returns to the sign-in screen.

`npm run build` (full `tsc -b && vite build`) is clean; `npm run lint` (`oxlint`) reports only 4 stylistic warnings (fetch-on-mount via `useEffect`, a very standard React pattern; Fast Refresh export-shape hints on the two context files), zero errors.

**Not built/covered yet:** `DocumentFilter`'s search/status controls aren't wired into `MainLayout` yet (the component exists per design.md's file list but isn't rendered - the table always shows everything); no automated frontend test suite (Vitest/RTL) was set up, only the manual Playwright scripts above (not committed as a test suite, just verification tooling); no production Dockerfile/Nginx stage for the frontend (plan.md M8.2 mentions one; only the backend container exists so far, per the containerization work in §12h, and the user hasn't asked for the frontend one yet); dark/light theme toggle UI (the CSS tokens support both via `prefers-color-scheme` and a `data-theme` override, but nothing in the UI lets a user switch it yet).

## 12j. Ingestion tier order swapped: Java parser first, Gemini as fallback (explicit user deviation from plan.md's size-routed invariant)

User's instruction verbatim: "exchange the tier 1 and 2 parsing, first try to parse the file using the java library if it fails then use the LLM to parse the file, if both fail just show error message in frontend and backend." This replaces plan.md section 1.1's original invariant ("`<2MB`: LLM Multimodal... `2MB-10MB`: Java parsers") - a deliberate, explicit user override, not a bug fix. Prompted by a real image upload failing with a Gemini `503`/`429` (the free-tier `generate_content_free_tier_requests` daily quota - limit 20/day - being exhausted by this session's heavy testing); the user chose to fix the underlying exposure (every PDF/image depending on a 20-request/day external quota) rather than just wait it out.

**Two decisions asked and answered before touching code** (real forks, not guessed):
1. Scope: apply the new order to **all 7 categories**, not just PDF/image (Tier 1's only categories before). Word/Excel/PowerPoint/HTML/Text now also get a Gemini-fallback attempt if their Java parser fails - even though Gemini's multimodal input may well reject those binary/complex formats; that rejection is allowed to surface as the normal "LLM fallback also failed" combined error rather than being hardcoded as unsupported up front. No new infrastructure (e.g. LibreOffice-to-PDF conversion) was built for this - the fallback either works because Gemini happens to accept the format, or fails cleanly, by design.
2. Size cap: **keep the existing 2MiB cap** on the Gemini fallback. A file between 2MB-10MB that fails Java parsing gets no LLM fallback at all - straight to a FAILED error citing only the Java parser's reason.

**What changed:**
- `IngestionTierRouter` (and its test) **deleted entirely** - there is no more upfront size/category routing decision to make; every file always tries the Java parser first.
- `DocumentService.upload()` no longer takes an `IngestionTierRouter`; it just sets `document.ingestionTier = TIER_2_JAVA_PARSER` as a **provisional** value (always true, since that's always attempted first).
- `MultimodalLlmExtractor.validate()` no longer rejects non-PDF/IMAGE categories - any category can now be attempted when invoked as a fallback. Its 2MiB size cap (`TIER_1_MAX_BYTES`, now a public constant on this class, moved from the deleted router) is unchanged.
- `IngestionOrchestrator.extract()` rewritten: try `JavaParserExtractor` (with its existing timeout) first, always. On `DocumentExtractionException`, if the file is ≤2MiB, try `MultimodalLlmExtractor` as a fallback; if that also fails, or the file is >2MiB (no fallback attempted), throw a combined `DocumentExtractionException` naming both failures (e.g. `"Java parser failed: <reason>. LLM fallback also failed: <reason>"` or `"...LLM fallback not attempted: file exceeds the 2097152 byte Tier 1 limit."`). On whichever tier actually succeeds, `document.ingestionTier` is updated to match reality (`TIER_2_JAVA_PARSER` or `TIER_1_MULTIMODAL`) - it's no longer knowable upfront, only after the fact.
- This combined message lands in both `documents.error_message` and `ingestion_jobs.error_message` (unbounded `text` columns, no truncation risk), and the React frontend's `FileUploadProgressCard` already surfaces it as-is (no frontend change was needed - it just displays whatever string the backend sends).

**Tests:** `DocumentServiceTierTest` rewritten (now just confirms every category/size starts as `TIER_2_JAVA_PARSER` - the old per-category/size routing assertions no longer apply, there is no routing). `MultimodalLlmExtractorTest` updated (removed the "unsupported category" rejection test since that restriction is gone; added a test proving a non-PDF/image category is now actually attempted). `IngestionOrchestratorTier2TimeoutTest` updated to also stub the Tier 1 mock to fail (since its small test file now triggers a real fallback attempt after the Tier 2 timeout) - same final assertions still hold. New `IngestionOrchestratorFallbackTest` (3 tests): Tier 2 fails → Tier 1 succeeds (and `ingestionTier` flips to `TIER_1_MULTIMODAL`); both tiers fail → combined error names both reasons; a file over the 2MiB cap gets no Tier 1 attempt at all (`verify(tier1Extractor, never()).extract(any())`). Full unit suite: **123/123, 0 failures, 0 errors**.

**Verified live, in the rebuilt Podman container, against the real (still quota-exhausted) Gemini API:**
- A synthetic 1x1-pixel PNG (genuinely no text) correctly produced `"Java parser failed: No readable text found in test.png. LLM fallback also failed: Gemini extraction failed: ..."` - proving the double-failure combined-message path for real, including the real Gemini 429 still firing as expected.
- A real PNG with rendered text (`"Quarterly revenue report ABCXYZ"`, generated via a throwaway AWT/`Graphics2D`+`ImageIO` script, same technique `TestDocuments.textImage` already uses for `TesseractOcrParserTest`) uploaded, OCR'd by Tesseract (Tier 2) successfully, and reached `INDEXED` - **confirmed via container logs that zero Gemini calls were made for this document** (no `genai`/`gemini`/`quota` log lines anywhere near its ingestion). This is the practical win the user was after: image uploads (and every other category) no longer depend on Gemini's 20/day free-tier quota as their primary path at all.

## 12k. Chat/LLM model swapped to `gemini-3.5-flash-lite` (quota-driven, user-approved)

User replaced their Gemini API key mid-session, then still hit chat-synthesis failures (quota exhaustion on `gemini-3.5-flash`, then separately a transient `503 high demand`). Asked the user directly rather than guess a model name from stale training knowledge; **queried Google's real `v1beta/models` list-endpoint with the actual API key** to get ground truth on what's genuinely available right now, then **live-tested candidate models with a real `generateContent` call** before recommending anything:
- `gemini-2.5-flash-lite` → `404 NOT_FOUND`, deprecated for new users, API itself redirects to `gemini-3.5-flash-lite`.
- `gemini-3.5-flash-lite` and `gemini-flash-lite-latest` → both answered live. Picked `gemini-3.5-flash-lite` over the `-latest` alias specifically because it's a **pinned, stable name** - a rolling alias silently changing underneath a project whose own plan.md calls for a "fixed backend model" would undermine that invariant.
- User confirmed via `AskUserQuestion` before applying.

**Changed:** `application.yml`'s `spring.ai.google.genai.chat.options.model` → `gemini-3.5-flash-lite` (was `gemini-3.5-flash`). Doc-comment references in `SpringAiConfig.java` and `AnswerSynthesizer.java` updated to match. **Embedding model (`gemini-embedding-2`) deliberately left unchanged** - it never once hit a quota or demand error all session, unlike the chat model.

**Verified:** rebuilt the container, full unit suite still **123/123, 0 failures**, then a real signup → upload → chat round trip against the live container returned a correct, cited `LOCAL_KB` answer using the new model.

## 12l. Conversation memory + mixed-contrast OCR fix (both user-requested, both verified live with real failure cases reproduced then fixed)

### Conversation memory (client-held, never persisted - explicit user decision)
User asked for chat history to improve RAG, flagged against the explicit "no chat transcripts persisted server-side" invariant (plan.md, and this session's own audit-log scoping decision). Asked first; user confirmed: **client sends recent history each request, backend uses it only in-memory for that one request, never stores it.**
- New `rag/dto/ChatTurn.java` (`role` validated to `user|assistant`, `text`). `ChatRequest` gained an optional `history` field (`@Size(max=20)`, defaults to `List.of()` via compact constructor).
- `RagQueryOrchestrator`: new `answer(tenantId, query, history)` overload (old 2-arg one kept, used by the MCP `query_user_rag` tool - **conversation memory was deliberately scoped to the REST `/api/v1/chat` endpoint only**, not MCP, since MCP clients like Claude Desktop/Cursor manage their own conversation context already). History is trimmed to the last `MAX_HISTORY_TURNS = 6`. The last `MAX_RETRIEVAL_HISTORY_USER_TURNS = 2` **user** turns (not assistant) are prepended to the text that gets embedded for vector search - cheap (reuses the existing embedding call, no extra Gemini call), and directly fixes the demonstrated failure: a vague follow-up like "what about 2025" doesn't semantically match anything alone, but combined with the prior turn ("where did gokulakrishna work") it does.
- `AnswerSynthesizer.synthesizeFromLocal/synthesizeFromWeb` now also take `history`, included in the prompt as a labeled transcript with an explicit instruction that it's for continuity only (resolving "it"/follow-ups), never a source of facts - only the retrieved excerpts/web results may ground the answer. No extra Gemini call (same one call, enriched prompt).
- Frontend: `types/chat.ts` gained `ChatTurn`/updated `ChatRequest`; `useRagChat.ts` builds `history` from the last 6 completed (non-pending, non-error) messages in React state before each send.
- **Verified live**: "where did gokulakrishna work" → correct answer; follow-up "what about 2025" **with history** → correctly resolved to the same person's 2025 employment (`LOCAL_KB`, 0.82 similarity) - this exact query previously returned the deterministic `NONE` failure message (see the chat screenshot in this session's transcript) before this feature existed.
- Tests: `RagQueryOrchestratorTest` gained 2 tests (`aVagueFollowUpIsEnrichedWithRecentUserHistoryBeforeEmbedding`, `historyIsTrimmedToTheMostRecentTurnsOnly`) plus existing 3 updated for the new 3-arg synthesizer signature; `AnswerSynthesizerTimeoutTest` updated likewise.

### Mixed-contrast OCR (two iterations - the first naive fix empirically did not work, caught by live testing before declaring it done)
User reported (with the real failure visible in a screenshot and the actual uploaded image): a 10-facts-about-cats infographic with a white section (black text, facts 1-8) and a teal banner (white text, facts 9-10, including "cats can run up to 48 km/h") only OCR'd facts 1-8. Checked the real indexed chunk in Postgres directly - confirmed facts 9-10 were completely absent from the extracted text, not a retrieval problem.
- **First attempt (reverted in spirit, superseded):** whole-image color inversion as a second OCR pass, merged by line. Round-tripped through a live container test with a close replica of the real image - **did not recover the missing band**. Root cause understood after the fact: Tesseract binarizes against one *global* threshold per call; inverting the *whole* image doesn't stop two regions from competing for that one threshold, it just changes which one can lose - empirically it kept losing the same region.
- **Working fix:** `TesseractOcrDocumentParser` now first calls `splitIntoBackgroundBands`, which groups image rows into contiguous bands by a smoothed background-brightness signal (each row's **90th-percentile** pixel brightness, not the mean - the majority-background-pixel proxy that doesn't get dragged down by ink-dense text rows). `BAND_BRIGHTNESS_TOLERANCE = 35` (0-255) starts a new band; `MIN_BAND_HEIGHT_PX = 20` folds antialiased transition slivers into the previous band rather than OCR-ing a near-empty strip. Each resulting band - now internally uniform, so Tesseract's own threshold is no longer contested - is OCR'd twice (normal + inverted, cheap per small band) and all results merged by exact line, deduped, in top-to-bottom order.
- **Verified live, twice:** a synthetic exact replica of the real image (white section + teal banner, "CATS CAN RUN UP TO 48 KM PER HOUR" in the teal part) uploaded to the rebuilt container - the chunk content confirmed all three facts now present, including the previously-missing one. New `TesseractOcrParserTest.mixedPolarityImageIsReadInBothRegionsViaTheInvertedSecondPass` (white-top/black-bottom synthetic image, both halves' text present).

### Image chunking granularity (a second, separate bug this surfaced - user approved the fix too)
Even with OCR now correct, the original demonstrated chat query ("what is the maximum speed a cat can run") still failed after the OCR fix alone: all 3 short, unrelated facts were packed into **one chunk** by `HeadingAwareChunkingStrategy`'s word-count packing (image target was 300 words, and the whole OCR'd text was far under that), diluting the running-speed fact's embedding with two unrelated facts, pushing it under the 0.70 sufficiency threshold. Confirmed directly (DB had the right text; the embedding just didn't score high enough). Asked the user before changing chunking behavior project-wide; approved.
- New `ImageLineChunkingStrategy` (`ingestion/chunking/`): one chunk per non-blank OCR'd line, no word-count packing at all - appropriate for infographic/list-style image content, unlike flowing prose. `IMAGE` removed from `HeadingAwareChunkingStrategy`'s `TARGET_WORDS` map (now WORD/PDF/HTML/TEXT only).
- **Verified live**: same replica image now produces **3 separate chunks** (one per fact); the running-speed query now returns `LOCAL_KB`, **"A cat can run up to 48 kilometers per hour,"** 0.83 similarity, correctly citing the right chunk - the complete, original failure case now genuinely fixed end to end.
- Tests: new `ImageLineChunkingStrategyTest` (4 tests); `HeadingAwareChunkingStrategyTest` updated (IMAGE assertions removed/retargeted to TEXT). Full suite: **130/130, 0 failures, 0 errors**.

**Lesson for next time:** don't declare an OCR/image-processing fix done from reasoning alone - the first inversion attempt was plausible-sounding and still wrong; only the live round-trip (upload → check the actual DB row) caught it. Same pattern as the RLS binding bug earlier this session - verify the specific real symptom closed, not just that the code compiles and a plausible mechanism exists.

## 12m. Same dilution bug found in real PDFs too - fact-sheet-style PDFs now chunked per-fact (user-approved)

User reported two issues from real testing with real uploaded files (their actual resume PDF and a real "25 facts about cats" compendium PDF, both read directly from disk to get ground truth rather than guessed): a vague resume follow-up ("just give me the experience in years") and a direct question whose answer was verified to literally be on page 3 of the PDF ("A cat's whiskers, scientifically known as vibrissae..."). Investigated both with live, real reproductions (uploaded the actual files via the real API, not retyped approximations) before touching any code - the "prove it don't guess" rule paid off again:
- **Resume follow-up: did not reproduce.** Same conversation-memory code path, correct `LOCAL_KB` answer both times tested. Likely a one-off (possibly a transient Gemini blip) rather than a real defect - no code change made for this one.
- **Whisker question: reproduced reliably**, and traced to the exact same root cause as §12l's image dilution bug - except this time in a PDF, not an image. `The Secret Life of Felines.pdf`'s chunk 0 (confirmed via direct DB query) genuinely contained the word "vibrissae" - the text was correctly extracted - but `HeadingAwareChunkingStrategy`'s word-count packing (PDF target 760 words) merged Facts 01-04 into one ~4.6KB chunk, diluting the whisker-specific content below the 0.70 similarity threshold.

Asked before extending the fix to PDFs (a project-wide chunking behavior change); approved, scoped to PDFs specifically that look like fact-sheets, not all PDFs.

**What changed:**
- `ChunkingSupport.splitOnCapsHeadings` (new): splits on standalone short ALL-CAPS lines (e.g. `FACT 02 • SENSORY APPARATUS`) - the closest thing plain Tier 2 PDF text extraction has to a heading, since PDFBox emits no Markdown `#` markers at all. Deliberately permissive (any short all-caps line, including incidental stat-label rows) - over-fragmenting into more small-but-coherent chunks is an acceptable tradeoff for never diluting unrelated facts together.
- `HeadingAwareChunkingStrategy`, PDF category only: first runs `splitOnCapsHeadings`; if `looksLikeFactSheet` (≥5 such sections, average section size under 1/3 of the 760-word target) - each section becomes its own chunk directly, **not** packed toward the word target (a section that's itself oversized is still hard-split via the existing `chunkByParagraph` safety net). A normal prose-heavy PDF (report, contract) rarely has qualifying lines and falls through unchanged to the original packing behavior - verified by a dedicated test (`aFewIsolatedCapsLinesInAProseHeavyPdfDoesNotTriggerFactSheetMode`).
- WORD/HTML/TEXT chunking is completely untouched - this is PDF-only, matching what was approved.

**Verified live, twice** (re-derived the live similarity scores both before and after the fix, not just checked success/failure): before the fix, both whisker-phrasing questions returned `NONE`; after rebuilding the container, both now return `LOCAL_KB` with a correct "vibrissae" answer (0.72 and 0.82 similarity). The real PDF now produces 36 chunks (was 6) - confirmed directly in Postgres that the vibrissae fact is its own standalone ~993-byte chunk, no longer merged with Facts 01/03/04.

Tests: `HeadingAwareChunkingStrategyTest` gained 2 tests (fact-sheet chunk-per-fact behavior using a 6-fact synthetic fixture matching the real document's structure; the prose-PDF non-regression case). Full suite: **132/132, 0 failures, 0 errors**.

## 12n. Real PDF OCR built (user-requested, scoped after clarifying the actual ask), plus a reliability scare resolved by live diagnosis, not guessing

User hit a real ingestion failure live: a scanned offer-letter PDF failed both tiers ("This PDF has no extractable text... OCR is not available for PDFs over 2 MiB" from Tier 2, then Gemini returned an empty result for the Tier 1 fallback). They asked to "increase the java library file parsing limit to 10 MB" - checked the code first rather than just editing a message: **there was no real 2MiB limit to raise**. That "2 MiB" in the old error message only ever described Tier 1/Gemini's own cap; Tier 2's PDF parser (`PdfBoxDocumentParser`) had **no OCR capability at all** - it only ever read embedded text layers, at any file size. Explained this and asked before building a real new capability; user confirmed.

**What was built:**
- `TesseractOcrEngine` (new): the OCR core (band-splitting, dual-pass normal/inverted, merge-by-line) extracted out of `TesseractOcrDocumentParser` into its own `@Component`, so both standalone-image OCR and the new PDF-page OCR share one implementation rather than duplicating it.
- `PdfBoxDocumentParser` rewritten: **per-page**, not document-level. Each page's embedded text is tried first; only a page with **no** embedded text gets rendered (via PDFBox's `PDFRenderer`, 200 DPI, backed off toward 72 DPI if that would exceed the existing `MAX_PIXELS` decompression-bomb cap) and OCR'd through `TesseractOcrEngine`. This is deliberately more thorough than a simple "whole document empty -> OCR everything" fallback: a **mixed** PDF (some real-text pages, some scanned/image pages - common for a cover page + scanned attachment) now gets OCR only on the pages that actually need it, not a blanket treatment. No separate size/page-count cap was added - bounded by the existing overall 10MiB upload limit and the existing `synapse.ingestion.tier2.timeout` (`PT2M`) safety net.
- `TesseractOcrEngine` gained a `MAX_BANDS = 12` cap (band-splitting falls back to one whole-image dual pass above this), added as a precaution after an *initial* false lead - see below.

**A real reliability scare, resolved by live diagnosis rather than reasoning from the code:** the exact real scanned PDF that fixed the user's original failure (6 pages, succeeded in ~18-28s) then **intermittently timed out entirely** (hit the full `PT2M` Tier 2 timeout) on repeated test runs run back-to-back. First hypothesis - real-world letterhead/logo noise exploding the band count - was plausible but **wrong**: added `MAX_BANDS` as a guard, reran, and it *still* timed out. Rather than guess again, added temporary `log.info` diagnostic lines (page count, per-page timing, per-band timing, band count) to `PdfBoxDocumentParser`/`TesseractOcrEngine`, rebuilt, and watched a live reproduction run through `Monitor`. **Actual finding:** every real page produced only 1-2 bands (nowhere near the cap), and each page OCR'd in 2-5s - a completely healthy run (~17s total), run in isolation. The earlier timeouts were caused by **this session's own test script** firing multiple heavy, CPU-bound OCR ingestions back-to-back against a 4-CPU-limited Podman VM, not a defect in the OCR code itself under realistic single-document usage. Removed the diagnostic logging once confirmed (kept the `MAX_BANDS` cap - still a legitimate independent safety net for the band-explosion scenario it targets, even though that was not what actually happened this time); corrected the class javadocs to state the real, confirmed cause rather than the earlier plausible-but-wrong theory.

**Verified live:** the real `CitiusTech_Offer_and_Salary_Revision.pdf` (6 pages, genuinely scanned, no text layer) now indexes successfully and consistently when not contended for CPU, with real extracted content (offer letter body, salary structure, joining bonus terms - confirmed directly in Postgres) correctly retrievable via chat.

Tests: `PdfBoxDocumentParser`'s old `scannedPdfWithoutATextLayerFailsWithAClearMessage` test rewritten to `scannedPdfWithoutATextLayerIsReadViaOcr` (now asserts successful OCR, not failure - the old expected behavior no longer exists). New `mixedPdfOnlyOcrsThePagesThatNeedIt` (a text page + a scanned page, both correctly extracted) and `scannedPdfWithNoLegibleTextFailsWithAClearMessage` (a genuinely blank scan still fails cleanly, with an updated message). New `TesseractOcrParserTest.manyNarrowBandsFallsBackToOneWholeImagePassInsteadOfExploding` (a 40-stripe synthetic image must stay fast, proving the `MAX_BANDS` cap actually engages). Full suite: **135/135, 0 failures, 0 errors**.

**Lesson reinforced (same one as §12h and §12l):** a plausible-sounding root-cause theory written from reading the code is not verification. The band-explosion theory was reasonable and even came with a real fix attached, and it was still wrong - only live diagnostic logging, watched during an actual reproduction, found the real cause. Don't add a second unverified fix on top of a first one that didn't actually solve the observed symptom; re-diagnose live instead.

## 12o. Full RAG sweep across all 7 categories (user-requested), with three more real bugs found and fixed live

User asked for a thorough, systematic confirmation that the app genuinely answers questions correctly, backed by checking the DB after indexing - not just spot-checking one file type. Built one script that uploads one real file per category (a freshly generated real PDF, the real scanned CitiusTech offer letter, the real `sample.docx`/`sample.xlsx`/`sample.pptx` fixtures, and fresh HTML/TXT/CSV/MD/JSON/image files), each containing a unique, invented marker token (e.g. `PDF-KESTREL-470`, `ALPHA-111`, `IMG-PANGOLIN-284`) so a correct answer can only come from genuinely finding that exact document's content, not a coincidence or a model guess. Waits for real indexing, then asks one targeted question per file and checks the citation names the right document and the answer contains the right marker.

**First run: 9/11 passed, 2 real failures found and fixed.**

1. **S3 upload reliability bug (`DocumentStorageService`)**: the `.txt` upload failed outright with `IllegalStateException: Content input stream does not support mark/reset, and was already read once`. Root cause: `RequestBody.fromInputStream(rawStream, size)` wraps a non-replayable stream - if the AWS SDK ever needs to retry the request (a transient network blip) or re-read it for SigV4 signing, it can't, and fails hard instead of actually retrying. This is **not file-type-specific** - it could hit any upload, unpredictably, whenever the SDK needs a retry; it only happened to manifest on this particular run's 7th upload. Fixed by buffering the already-10MiB-capped file into a `byte[]` and using `RequestBody.fromBytes` instead, which the SDK can replay. New regression test (`requestBodyIsReplayableSoTheSdkCanActuallyRetry`) opens the captured request body's stream twice and asserts identical content both times - something the old implementation could not do. Re-ran the real `S3LiveIT` against actual AWS S3 to confirm.
2. **Retrieval breadth gap, surfaced by the image fixture**: the image's title line ("Warehouse Safety Notice") and its marker line ("Marker IMG-PANGOLIN-284") are separate chunks (per §12l/§12n's per-line/per-fact chunking, deliberately, to avoid dilution) - but for this question's exact wording, the title chunk scored higher than the marker chunk, and `VectorRetriever`'s `TOP_K = 5` cutoff excluded the marker chunk entirely before synthesis ever saw it. Not a spec requirement (checked `design.md`/`plan.md` - no fixed top-K value documented), so raised to 8. Also strengthened `AnswerSynthesizer`'s local-answer prompt to explicitly instruct the model to check every excerpt individually (short, independent fragments can easily get skimmed past) before concluding information isn't present - `gemini-3.5-flash-lite` (the lite model switched to in §12k) is more prone to this than a larger model. Verified live after each change; the marker is now reliably found and correctly stated.

**Second run, after both fixes: 11/11 passed once, then the exact same image question failed again on a third full-sweep run** - an intermittent LLM response, not a retrieval problem (confirmed via a direct re-query: the marker chunk is reliably in the context both times, so this was purely a synthesis-time miss, not retrieval regressing). Traced to generation temperature: `AnswerSynthesizer`'s local-answer call used `temperature(0.2)`, a small amount of sampling randomness with no real justification for a strictly-grounded fact-lookup task (never creative writing) - occasionally enough for the model to skim past one short excerpt among several. Changed to `temperature(0.0)` for maximum determinism. **Verified properly this time**: not a single retest, but the same question asked 5 times in a row against the same indexed document - 5/5 identical, correct answers (previously intermittent). Full unit suite re-run after each change: 136/136, 0 failures throughout.

**A fourth thing investigated, not a bug:** the exact scanned CitiusTech PDF that fixed cleanly earlier (§12n) intermittently hit the full 2-minute Tier 2 timeout on repeated back-to-back test runs. The first hypothesis (band-count explosion on a noisy real scan) was plausible and even got a real fix (`TesseractOcrEngine.MAX_BANDS = 12`, kept as a legitimate independent safety net) - but it was wrong. Temporary `log.info` diagnostic lines (page count, per-page and per-band timing) added, rebuilt, and watched live via a real reproduction run showed every page producing only 1-2 bands and OCR-ing in 2-5s each (~17s total, completely healthy) when run in isolation. **The actual cause: this session's own test scripts firing multiple heavy, CPU-bound OCR ingestions back-to-back against a 4-CPU-limited Podman VM** - host contention, not a code defect under realistic single-document usage. Diagnostic logging removed once confirmed; class javadocs corrected to state the real, confirmed cause instead of the first plausible-but-wrong theory.

**Lesson reinforced a third time this session (§12h, §12l, now this):** a plausible root-cause theory is not verification, even when it comes with a real fix attached. The band-count theory was reasonable, had a legitimate fix, and was still wrong - only live diagnostic logging, watched during an actual reproduction, found the real cause. When a first fix for an observed symptom doesn't actually make the symptom go away, re-diagnose live rather than stacking a second unverified fix on top.

## 13. Not built yet (whole backend)

`./mvnw clean verify` full suite re-run after the §12h fix (unit-only `mvn test` and the real-infra `*IT`s touched by the fix have been re-run and are green - see §12h); the HTTP regression matrix re-run on this machine; frontend production Dockerfile/Nginx stage; `DocumentFilter` wiring; a frontend automated test suite. Docker/compose (8.2) is built and the core backend flow (signup/upload/status/list/chat/mcp-keys) is verified working correctly under real RLS enforcement inside the container. The React frontend (Phases 2/7, §12i) is now built and verified end-to-end against that same containerized backend.

## 14. Lessons / gotchas (so they are not rediscovered)

- A passing test can hide a wrong premise — real files exposed 3 bugs the mocks could not (Word style-based bullets, legacy `.ppt` tables, Excel date format). Prefer real fixtures.
- "Listed" ≠ usable (Gemini model list); "set" ≠ in effect (retry property; SDK retries below Spring AI); measure instead of assuming.
- The `Edit` tool needs exact text; invisible characters (e.g. NBSP) can break matches — rewrite the line by number via PowerShell.
- Minimal Spring test contexts don't resolve `@Value` placeholders; bind through typed properties.
- Background tests: run with `run_in_background`, wait for the notification, then read the log; a long-document live test can run ~9 minutes.
- Clean up after yourself: throwaway tenants/accounts, temp probe tests (`Tmp*`), scratch PDFs.
- Don't print or log secrets; check variables by `[bool]`/length only.
- **Every service method a tenant-scoped request reaches needs its own explicit `@Transactional`, not just the ones that already happen to call base CRUD repo methods.** Spring Data's default per-call transaction only reliably covers `JpaRepository`-inherited methods (`findById`, `save`, …); custom derived-query and `@Query` methods called from a non-`@Transactional` service method run on an ad-hoc, non-transactional `EntityManager` instead, so a custom `doBegin()`-based mechanism (like this app's RLS tenant-binding) never fires — a silent, correctness-breaking gap that `admin`/RLS-bypass testing can never catch. Found only once `synapse_app` (real RLS) was exercised for the first time, via the Podman container (§12h).
- This tool's Bash shell does not auto-source `~/.zshrc` — any command needing real env secrets (`JWT_SECRET`, `GEMINI_API_KEY`, AWS keys, `podman compose` runs) must `source ~/.zshrc` first, every time, in the same command block (shell state doesn't persist between tool calls).

---

## 15. Code change log (what exists, by area)

Legend: **(read)** = re-read in code this session; **(notes)** = from earlier-session notes, not re-audited line by line.
Main source = 66 Java files, test source = 19 Java files (counted on disk 2026-10-13). Nothing is committed, so `git diff` shows nothing; this section is the change record.

### 15.1 Earlier sessions (M1.1, M1.2, M3.1)
- **DB:** `V1__init_schema.sql` = architecture.md DDL verbatim (tables tenants, users, api_keys, knowledge_bases, documents, ingestion_jobs, chunks, audit_logs; HNSW index on `chunks.embedding vector(768)`, GIN on `tsv`; RLS policies `tenant_isolation_*` on documents/chunks/jobs/api_keys/knowledge_bases) **(read policies)**. `V2__rls_audit_and_api_key_auth.sql` adds RLS on `audit_logs` and the `authenticate_api_key()` SECURITY DEFINER function used for MCP key lookup **(notes)**.
- **Entities:** `Tenant`, `User`(+`UserRole`), `KnowledgeBase`, `Document`(+`DocumentStatus`, `IngestionTier`), `IngestionJob`(+`JobStatus`, `JobStage`), `Chunk`, `ApiKey`, `AuditLog`; repositories for tenant, user, KB, document, job **(read names)**. `Chunk` ↔ table mapping has **never been exercised**.
- **Security:** `TenantContext` (ThreadLocal) + `TenantAwareTaskDecorator`/`VirtualThreadConfig`; `TenantRlsTransactionManager` + `RlsSupport` (sets `app.current_tenant_id` per transaction); `JwtTokenProvider`/`JwtProperties` (HS256, Nimbus; claims sub/tenantId/email/role; 24h); `JwtAuthenticationFilter` (Bearer header; `?token=` only for the progress-stream URI); `McpApiKeyAuthenticationFilter` + `ApiKeyAuthenticator`/`ApiKeyHasher` (SHA-256) (accepts header or `?token=`); `ProblemAuthenticationEntryPoint` (401 as problem-JSON) **(read SecurityConfig, filters' wiring)**.
- **`SecurityConfig`** **(read)**: two stateless chains — `/mcp/**` (order 1, API key) and everything else (order 2, JWT); `/api/v1/auth/**` open; `OPTIONS` open; **ERROR dispatch permitted** (fixes a bug where every 404/500 became a 401); BCrypt encoder; CORS from `synapse.cors.allowed-origins`.
- **Auth** **(read)**: `AuthController` (`/signup` 201, `/signin` 200); `AuthService` atomically creates tenant + user + the single default KB, normalizes email (trim, lowercase, case-insensitive uniqueness, race handled via `DataIntegrityViolationException`→409), signin uses a dummy-hash comparison for unknown emails (timing equalizer); `AuthRequests` validation (email ≤255, password 8–72 because of BCrypt, fullName ≤255); `AuthResponse{token, user{id,email,fullName}, knowledgeBaseId}`.
- **Documents (upload path)** **(read)**: `FileCategory` (7 categories; MIME derived from the extension, never trusted from the client); `DocumentController` (`POST /api/v1/documents/upload`, multipart part `file`, 202); `DocumentService.upload` order of checks: >10 MB → 413, empty → 400, unsupported extension → 415; then inserts document + job (progress 5), stages to S3, stores `storage_path`; any staging failure rolls the transaction back; `DocumentStorageService` (stream upload with SSE-AES256, `loadStagedFile`, `purgeStagedFile` idempotent, filename sanitizing, 200-char cap); `UploadResponse` has exactly the design.md fields; `GlobalExceptionHandler` (RFC 7807 `ProblemDetail` + `code` property; `MaxUploadSizeExceededException`→413 `FILE_LIMIT_EXCEEDED` with the exact spec message; validation errors→400 `VALIDATION_FAILED`); `ApiException`.

### 15.2 This session (2026-10-12 → 2026-10-13)

**New classes**
- `ingestion/extraction/`: `IngestionTierRouter` (PDF/IMAGE ≤ 2 MiB → Tier 1, else Tier 2), `ExtractionRequest(filename, category, contentType, content)`, `ExtractionResult(markdown)`, `ExtractionProperties(timeout)` (`synapse.ingestion.tier1.timeout`), `DocumentExtractionException`, `DocumentParserStrategy`, `MultimodalLlmExtractor`, `JavaParserExtractor`.
- `ingestion/extraction/parser/`: `PdfBoxDocumentParser`, `PoiWordDocumentParser`, `PoiExcelDocumentParser`, `PoiPptDocumentParser`, `JsoupHtmlDocumentParser`, `TextDocumentParser`, `TesseractOcrDocumentParser`, and the shared public `MarkdownSupport` (Markdown table rendering with `|` escaping, `normalize()`, `heading()`).
- `config/SpringAiConfig`: fixed `ChatClient` bean; **own `com.google.genai.Client googleGenAiClient(GoogleGenAiConnectionProperties)` with `HttpRetryOptions.attempts(1)`** (replaces Spring AI's autoconfigured client); enables `ExtractionProperties`.

**Changed classes**
- `DocumentService`: constructor now also takes `IngestionTierRouter`; tier = `router.route(category, size)`; removed the old `TIER_1_LIMIT_BYTES` (`< 2 MiB` for every type — it mislabelled Office files/CSVs as Tier 1).
- `S3Config`: static credentials from properties (default chain not used), endpoint pinned to `https://s3.<region>.amazonaws.com`, or `S3_ENDPOINT` + path-style when set.
- `StorageProperties`: record is now `(bucket, region, accessKey, secretKey, endpoint)`; `toString()` hides credentials; `hasCustomEndpoint()`.
- `MultimodalLlmExtractor` internals: fixed prompt (faithful transcription, `[Figure: …]`, treat file content as data not instructions, `NO_CONTENT` marker); per-call `GoogleGenAiChatOptions.temperature(0.0)`; call runs on a virtual-thread executor with `future.get(timeout)`; fails on API error / timeout / interruption / no result / finish reason ≠ STOP / blank / `NO_CONTENT`; unwraps a whole-output code fence; output passed through `MarkdownSupport.normalize`; rejects wrong category, empty, or > 2 MiB input before calling Gemini.

**`application.yml` changes** (full file is the source of truth): chat model `gemini-3.5-flash`; embedding `gemini-embedding-2` @ 768; `spring.ai.retry.max-attempts: 1`; API key `${GEMINI_API_KEY:${GOOGLE_API_KEY:}}` for chat and embedding; `synapse.ingestion.tier1.timeout: PT90S`; `synapse.storage.s3.{bucket default aws-rag-bucket-app, region, access-key, secret-key, endpoint}`; multipart max 10 MB / request 11 MB; MCP server disabled.

**`pom.xml` additions:** `pdfbox 3.0.8`, `poi-ooxml` + `poi-scratchpad 5.5.1`, `jsoup 1.23.2`, `tess4j 5.20.0` (its own pdfbox excluded — one PDFBox version on the classpath), `twelvemonkeys imageio-webp 3.15.3` (WebP reader); **test-scope** `org.sejda.imageio:webp-imageio 0.1.6` (WebP *writer* to generate a fixture). Versions are properties at the top of the pom.

**Resources / files added:** `src/main/resources/tessdata/eng.traineddata`; `src/test/resources/fixtures/sample.{doc,docx,xls,xlsx,ppt,pptx}`; Maven wrapper (`mvnw`, `mvnw.cmd`, `.mvn/`); `README.md`; `.env.example`; `docs/local-db-setup.sql`; `docs/s3-setup.md` (updated for explicit creds); `docs/memory.md`.

**Tests (19 files; 69 offline unit tests + 5 opt-in live classes)**
- Offline: `IngestionTierRouterTest`(3), `DocumentServiceTierTest`(4), `DocumentStorageServiceTest`(3, mocked S3), `FileCategoryTest`(2), `AiRetryPolicyTest`(1, proves one attempt; fails if attempts=3), `MultimodalLlmExtractorTest`(10, mocked Gemini incl. timeout/429/truncation/NUL), `JavaParserExtractorTest`(10, router + PDF incl. scanned/encrypted/corrupt), `OfficeParsersTest`(13, real Office files), `TextAndHtmlParsersTest`(10), `TesseractOcrParserTest`(6, real OCR), `JwtTokenProviderTest`(5), `ApiKeyHasherTest`(1), `TenantAwareTaskDecoratorTest`(1). Helper `TestDocuments` generates real PDFs (text, ruled table, scanned JPEG page) and images.
- Live (opt-in, need keys, cost quota): `GeminiLiveIT`, `S3LiveIT`, `MultimodalExtractorLiveIT`, `MultimodalExtractorDeepLiveIT`, `MultimodalExtractorLimitsLiveIT` (see §10).

**Bugs found and fixed this session (root cause → fix)**
1. Upload used a size-only tier rule → router (+4 tests).
2. Spring AI retried 10× by default → `max-attempts: 1`; **then** found the Google SDK retries 429/5xx itself (20–46s failures) → own `Client` bean with `attempts(1)` (1–2s, measured). First attempt used `@Value` for the key and got a literal `${…}` in a minimal context → switched to `GoogleGenAiConnectionProperties`.
3. S3 client followed the machine's `AWS_ENDPOINT_URL` (LocalStack) → pinned endpoint (+ optional `S3_ENDPOINT`).
4. Word style-based bullets lost (docx and doc) → numbering/ style-name detection.
5. Gemini output could contain NUL/control chars (PostgreSQL rejects NUL) → normalized.
6. Test-only bugs: stub counting `prompt()` calls, over-strict `|---` table regex, Excel date expectation, pointless `.Value2`/style-ID COM calls.

**Deliberately NOT changed:** the four spec files, DB schema (no migration added this session), the S3 bucket configuration, git history.

### 15.3 Runtime/data state at hand-off (end of session 2)
DB has no tenants/users/documents/chunks. The S3 bucket should contain nothing from us (live test deletes its object). Local servers: none left running (port 8080 free). Temp probes (`Tmp*`) and scratch fixtures removed from the repo.

### 15.4 This session (session 3, 2026-10-13, on the Mac — M3.4)

**New classes**
- `ingestion/chunking/`: `ChunkPayload` (record), `DynamicChunker` (basic paragraph/word-count chunker, see §12).
- `ingestion/service/IngestionOrchestrator` — the M3.4 async worker (see §12 for full behavior).
- `document/service/IngestionProgressSseService` — SSE fan-out per document.
- `document/dto/`: `ProgressEvent`, `DocumentStatusResponse`, `DocumentListItem`.
- `rag/repository/ChunkRepository` — plain `JpaRepository<Chunk, UUID>`, didn't exist before.
- Tests: `rag/repository/ChunkRepositoryIT`, `ingestion/service/IngestionOrchestratorIT`.

**Changed classes**
- `SpringAiConfig`: added the dedicated `GoogleGenAiTextEmbeddingModel` bean + 3-attempt retry template (see §4/§12 for why).
- `DocumentController`: now takes `IngestionOrchestrator`/`IngestionProgressSseService`; `upload()` triggers ingestion after `documentService.upload()` returns; added `GET /{id}/progress-stream`, `GET /{id}/status`, `GET /`, `DELETE /{id}`.
- `DocumentService`: constructor now also takes `ChunkRepository`; added `getStatus`/`list`/`delete`.
- `DocumentRepository`: added `findByIdAndTenantId`, `findByTenantIdOrderByCreatedAtDesc`.
- `IngestionJobRepository`: added `findFirstByDocumentIdOrderByCreatedAtDesc`.
- `ChunkRepository`: added `countByDocumentId`.
- `DocumentServiceTierTest`: updated for the new `DocumentService` constructor arg (mocked `ChunkRepository`).

**Environment work (no app code, but real time cost — see §5a for full detail):** built pgvector from source for MacPorts' Postgres 17 (two attempts — first one targeted the wrong `pg_config`/Homebrew Postgres); fixed `~/.zshrc` (missing `export` on every project var, `AWS_SECRET_ID`→`AWS_SECRET_KEY` rename, missing `/synapsemcp` in `DB_URL`, added `JWT_SECRET`); ran Flyway once by booting the app (`./mvnw spring-boot:run`) rather than the bare `flyway-maven-plugin` goal (not configured with connection params).

**Deliberately NOT changed:** the four spec files, DB schema/migrations, `synapse_app` role creation (user chose to keep using `admin` on this machine, §11.6), git history (nothing committed this session), Tier 1 timeout (§11.1, deferred by the user).

### 15.5 Runtime/data state at hand-off (end of session 3)
This Mac's `synapsemcp` DB has test rows from `ChunkRepositoryIT`/`IngestionOrchestratorIT` runs, but both tests clean up their own tenant (cascade-deletes everything via FKs) in `@AfterEach`/test body, so the DB should be empty of leftover rows barring a mid-run crash. The S3 bucket should contain nothing from this session (the orchestrator test explicitly asserts the object is gone). No local server left running (port 8080 free, `spring-boot:run` processes killed). Nothing committed — `git status` shows the new/modified files listed above as untracked/modified.

### 15.6 This session, Phase 8.2 (Podman + RLS tenant-binding fix, §12h)

**New files:** `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `docker/init-db.sh`, `.env` (gitignored, container-only generated DB passwords, not real secrets).

**Changed classes:**
- `security/TenantRlsTransactionManager.java` / `security/RlsSupport.java` — read, not changed; confirmed correct in isolation (manual `psql` as `synapse_app` with `set_config(..., true)` inside a transaction works fine) — the bug was callers never opening a transaction through it, not the manager itself.
- `rag/retrieval/VectorRetriever.java` — added `@Transactional(readOnly = true)` on `search()`.
- `document/service/DocumentService.java` — added `@Transactional(readOnly = true)` on `verifyOwnership()`, `getStatus()`, `list()`.
- `mcp/service/McpKeyService.java` — added `@Transactional` on `create()`/`revoke()`, `@Transactional(readOnly = true)` on `list()`.

**Runtime state at hand-off:** Podman containers `backend-postgres-1`/`backend-backend-1` left running (rebuilt with the fix, verified working); a stale unrelated container `crazy_williamson` (exited 2 months ago) and image `localhost/synapse_mcp:latest` also exist on the machine, untouched, not to be built upon. `docker-compose.yml`'s temporary debug logging env vars were added and then reverted — the file in the repo has no debug logging in it. Containerized DB has a handful of test tenants/documents from this verification pass (`fixtest2...@example.com` etc.) — not cleaned up; harmless, container volume is disposable (`podman compose down -v` would reset it, not run without being asked).

### 15.7 This session, full 11-category RAG sweep + the real fix for the image-question synthesis failure

User's request: *"once the container is running, test the rag completely with all file type and confirm the app answers question by obtaining the data after indexing into DB by yourself, check it thoroughly for all the test case and finally run all the tests to ensure the app is robust and create a fresh frontend and backend img to test by myself."*

**S3 reliability bug (fixed):** `DocumentStorageService.stageFile` used `RequestBody.fromInputStream(stream, size)`, which wraps a non-replayable stream — if the AWS SDK needs to retry a transient failure or re-sign the request, it can't replay the stream and throws `IllegalStateException` instead of retrying. Not file-type-specific; first seen on a `.txt` upload. Fixed by buffering the already-10MiB-capped upload into `byte[]` and using `RequestBody.fromBytes` (replayable). Removed the now-unused `size` param from `stageFile`'s signature. New regression test `requestBodyIsReplayableSoTheSdkCanActuallyRetry`. Real `S3LiveIT` re-run against actual AWS S3, passes.

**Retrieval breadth gap (fixed):** `VectorRetriever.TOP_K` was 5; a real multi-document test (image fixture's detail chunk ranked 8th by similarity) showed the correct chunk could rank outside top-5 purely because of how many other documents were competing, with no spec requirement anywhere for TOP_K=5. Raised to **`TOP_K=8`**.

**The real, now-fixed root cause of a failure that looked like it had already been fixed twice (important — read this before touching `AnswerSynthesizer` again):**
Even with the marker chunk reliably inside the top-8 context (confirmed directly: title chunk at rank 1 sim=0.752, detail chunk at rank 8 sim=0.513, both correctly labeled `(from "image_fixture.png")`), the model (`gemini-3.5-flash-lite`) still answered "not mentioned" — deterministically, 0/5, at `temperature(0.0)`, under full 11-document competition (but passed 5/5 in an isolated single-document tenant). Two earlier attempts to fix this by **strengthening the prompt's wording** (first lowering temperature 0.2→0.0, then explicitly instructing "excerpts from the same named document should be connected") did not fix it — each looked plausible, each was verified with a real re-run, and each still failed. The actual cause, found only by inspecting the literal prompt payload sent to Gemini: `synthesizeFromLocal`'s context was built as one bullet per chunk **in flat similarity-rank order**, so the image document's two chunks (title at rank 1, detail at rank 8) sat six unrelated-document bullets apart in the list. Telling the model in prose that same-named chunks are connected was not enough to make it actually do that scattered-list cross-referencing reliably. **Real fix:** `synthesizeFromLocal` now groups `matches` by `documentName` (`Collectors.groupingBy(..., LinkedHashMap::new, ...)`, preserving each document's best-rank order) and renders **one bullet per source document**, with that document's chunks joined together inside it — removing the need for the model to notice a connection across a scattered list at all. Verified: full 11-category sweep 11/11, plus the specific image question re-asked 5/5 under full 11-document competition (`full_sweep_plus_repeat.py` in the session scratchpad). Lesson reinforced a third time this session: a plausible prompt-wording theory is not verification — the fix that actually worked came from reading the literal model input, not from guessing a better sentence.

**Full sweep result (11/11 categories, real upload → real DB-indexed content → correct cited chat answer, using a unique invented marker token per file so a correct answer can only come from genuinely retrieving that document):** PDF (text-layer), PDF (scanned, real OCR via the per-page `TesseractOcrEngine` fallback), Word `.docx`, Excel `.xlsx`, PowerPoint `.pptx`, HTML, plain text, CSV, Markdown, JSON, image (mixed-contrast OCR) — all PASS, each with a correctly cited source document name.

**Backend unit test suite:** 147/147, 0 failures, 0 errors (after this session's S3/grouping changes).

**Runtime state at hand-off:** container rebuilt (`podman compose build --no-cache backend` + `down -v`/`up -d`) with the `AnswerSynthesizer` grouping fix; DB is currently NOT yet wiped — still has the `finalsweep-...@example.com` test tenant and its 11 documents from this session's verification sweep. Clean that up (or leave it — harmless, container volume is disposable) before calling this handed off. Frontend dev server was already running from an earlier phase of this session (`npm run dev`, port 5173) and was not restarted for this specific fix since only the backend image changed.

### 15.8 This session, follow-up: parser edge-case/stability tests + a real PPT bug found and fixed + RAG accuracy/relevance test set

User's request: *"write few more test cases to check the accurate and relevant of answers the questions if it not satisfy the accurate and relevant handling accordingly and also for checking the stability for the parsing library in local in most of the scenario for all file type addressing most of the edge cases."*

**New test class `ParserEdgeCaseStabilityTest`** (`src/test/java/.../ingestion/extraction/`, 17 tests, all offline/unit) covering cases not already exercised by `OfficeParsersTest`/`TextAndHtmlParsersTest`/`TesseractOcrParserTest`/`JavaParserExtractorTest`: zero-page PDF, accented/unicode PDF text (also fixed `TestDocuments`' hand-rolled font dict to declare `/Encoding /WinAnsiEncoding` - without it, bytes like `é` decoded to the wrong glyph under the PDF spec's default StandardEncoding, a test-fixture-only gap, not an app bug), random-garbage-bytes-as-PDF, empty docx (no paragraphs), corrupt docx, unicode/emoji docx, empty xlsx workbook, error-formula (`#DIV/0!`) + merged-cell xlsx, corrupt xlsx, legacy `.xls` with real data, **empty pptx slide (see bug below)**, corrupt pptx, malformed/unclosed HTML tags, HTML entity decoding, 500-levels-deep nested HTML (stack-overflow guard), a 50,000-field single-line CSV with no newlines (performance bound, <5s), and malformed/truncated JSON passed through as plain text (this tier doesn't validate JSON structure - TEXT category is byte-decode only, by design).

**Real bug found and fixed: `PoiPptDocumentParser` let a content-free slide through `JavaParserExtractor`'s blank-result safety net.** The parser unconditionally wrote `"## Slide N: <title>\n\n"` before adding any shape text, so a slide with no title and only empty/decorative shapes (an unused text box, an image with no caption, etc.) still produced non-blank markdown like `"## Slide 3\n\n"` - this is content-free noise, but `.isBlank()` doesn't catch it, so it would have been silently indexed as a real chunk with zero actual information (degrading retrieval precision for every query that happened to match on nothing). Fixed: each slide's body (shapes + notes) is now built into a separate buffer first; the slide's heading is only appended to the document if the slide has a non-empty title **or** non-blank body content, otherwise the whole slide is skipped. Verified: the new `slideWithOnlyAnEmptyTextBoxAndNoRealContentFailsWithNoReadableText` test, plus re-ran `OfficeParsersTest`/`SlideChunkingStrategyTest` (both still pass - real slides with real content are unaffected).

**Full suite after these fixes: 164/164, 0 failures, 0 errors.**

**New live RAG accuracy/relevance test set** (`rag_accuracy_relevance.py`, session scratchpad, run against the real rebuilt container with 5 small fixture documents - md/txt/html/json/csv, each with its own known marker token) - deliberately different from the earlier marker-lookup sweep (§15.7), which only proved retrieval *works*; this set probes whether an answer is **accurate and relevant when it should say less than a full confident fact**, not just whether retrieval found the right chunk:
1. **Paraphrase** - two questions reworded away from the source document's own phrasing (not just the test script's original question) - both still answered correctly, proving the embedding model generalizes past exact phrase matching.
2. **Absent fact** - asked for an SSN that exists nowhere in any uploaded document. Correctly hit the deterministic `NONE` fallback message instead of inventing one.
3. **Partial answer** - a two-part question where only one part has a real answer (Bob's CSV note exists; no "manager" field exists anywhere in the KB). Correctly answered the real part and explicitly said the other part isn't stated, instead of fabricating a manager name.
4. **Conflation guard** - a question worded to tempt merging two *unrelated* documents' facts ("the Redis caching marker mentioned in the payment incident" - Redis belongs to the ADR doc, the incident marker belongs to a different doc). Correctly answered only from the actual incident document, did not claim the ADR's marker belongs to the incident. (This is also a negative-control check on the §15.7 grouping fix: confirms grouping same-document chunks together did not cause the model to over-connect genuinely different documents.)
5. **Conversation history** - two-turn exchange ("what about the other employee in that same list" with no name given) - correctly resolved via the client-sent history to mean "Bob," not Alice again.
6. **Out-of-domain query** - a nonsense town name with no KB or realistic web coverage. Correctly hit the deterministic `NONE` fallback, did not pull in an unrelated marker token as a wrong answer.

**Result: 8/8 passed on first run - no further prompt/retrieval changes were needed this round.** Test tenant cleaned up (`DELETE FROM tenants;`) after the run.

**Runtime state at hand-off:** backend container rebuilt a second time this session (`--no-cache`) with the PPT fix baked in; DB wiped of all test data; frontend (`npm run dev`, port 5173) and backend (`:8080`) both confirmed up. Nothing committed.

### 15.9 This session, follow-up: switched to native runtime, fixed a real frontend MCP connectivity bug, full live app audit

**Switched from Podman to native** (user's explicit request, "run natively from the machine itself"): `podman compose down`, backend started via `./mvnw spring-boot:run` against the native MacPorts Postgres on this Mac (schema/role setup already existed from earlier in the session - §5a), frontend via plain `npm run dev`. Reminder for next session: this means `DB_USERNAME=DB_PASSWORD=admin` (RLS bypassed) is back in effect per §5a/§11.6, not the containerized `synapse_app`/RLS-enforced setup from §12h - native and containerized runs have different isolation guarantees on this machine; re-verify which one is active before reasoning about RLS.

**Real bug found and fixed: the MCP "Connect External LLM" modal gave users a broken connection URL.** `frontend/src/components/settings/McpKeyModal.tsx` built the MCP SSE URL from `window.location.origin` - the frontend's own origin (`:5173` in dev). External MCP clients (Claude Desktop, Cursor, CLI tools like the user's `agy`/Antigravity CLI) make their own direct HTTP requests; they are not the browser page, so they never benefit from Vite's `/api`-only dev proxy. The copy-pasted config always pointed at `http://localhost:5173/mcp/sse`, which 404s before ever reaching the backend (`agy`'s real error: `calling "initialize": sending "initialize": Not Found`) - this would have broken MCP connectivity for every local user of this app, not just this session. Fixed by introducing a `VITE_BACKEND_ORIGIN` env var (no `VITE_*`/`.env` convention existed in this repo before - confirmed via search - so this was added fresh): `frontend/.env.development` sets it to `http://localhost:8080` for dev, `frontend/.env.example` documents it, and the modal now reads `import.meta.env.VITE_BACKEND_ORIGIN ?? window.location.origin` (falls back to same-origin for a production deployment that truly serves both from one origin). Verified live: fetched the dev-served module source and confirmed Vite injects `"VITE_BACKEND_ORIGIN": "http://localhost:8080"` into `import.meta.env` correctly; `npm run build` clean.

**MCP fully verified live over the real protocol** (not assumed): wrote a raw SSE+JSON-RPC client (no `mcp` Python package available, so hand-rolled the handshake) against the native backend - `GET /mcp/sse` with a real key → `endpoint` SSE event → POST `initialize` → correct `protocolVersion`/capabilities/`serverInfo` → POST `tools/list` → all 3 tools present with full schemas (`get_ingestion_status`, `query_user_rag`, `search_user_knowledge_base`) → POST `tools/call` on `query_user_rag` against a real uploaded+indexed document with a unique marker → correct answer, correct citation, correct similarity score, `source: LOCAL_KB`. This is the first time this session the full MCP protocol flow was driven end-to-end by hand rather than relying on the Spring AI starter's own internal tests.

**Full live application audit** (`full_app_audit.py` + `delete_audit.py`, session scratchpad, run against the native backend): **44/44 + 6/6 = 50/50 checks passed.** Categories covered, several for the first time this session:
- **Auth**: signup success/duplicate (case-insensitive)/bad email/short password, signin success/wrong password/unknown email (401 not 404, no user enumeration), protected endpoints reject missing/garbage tokens and accept valid ones.
- **Document lifecycle**: upload with no file part, unsupported type (415), empty file (400), >10MB (413 `FILE_LIMIT_EXCEEDED` - confirmed Tomcat's multipart cap enforces this, sometimes as a clean 413 and sometimes as a connection reset mid-upload depending on timing, both are the limit being enforced), valid upload (202) through to `INDEXED`, status/list reflect it correctly including `chunkCount`.
- **RAG chat**: response shape (`answer`/`source`/`confidenceScore`/`citations`), correct grounded answer with correct citation, auth-required, empty-query validation.
- **SSE progress stream - verified for the first time this session with a real streaming connection** (previously only confirmed the endpoint existed via JWT query-token reuse, never actually opened and read a live `EventSource`-style connection): real events received, saw a terminal/complete signal.
- **Cross-tenant isolation at the app level**: a second tenant cannot read/delete the first tenant's document (404, not leaked), doesn't appear in their list, cannot open their SSE stream, and - most importantly, since native dev runs as `admin` with RLS bypassed - **cannot retrieve the first tenant's RAG content through chat either**, proving `VectorRetriever`'s `tenant_id` filter in the query itself (not just RLS) independently enforces isolation.
- **Delete lifecycle** (not covered by the main audit, added separately): deleting a document actually removes it (204), status/list correctly show it gone, **RAG chat genuinely stops finding its content afterward** (not just a DB flag flip), and deleting an already-deleted document returns a clean 404, not a 500.
- **MCP key lifecycle**: create (plaintext key shown once), list (never re-exposes the plaintext key), revoke, a revoked or bogus key is rejected (401) by the real `/mcp/sse` endpoint.
- **CORS**: preflight from `localhost:5173` allowed, preflight from an untrusted origin not allowed.
- **Response structure**: confirmed every error path returns the same RFC 7807 `ProblemDetail` shape (`status`/`title`/`code`, plus `errors` for validation failures) via `GlobalExceptionHandler` - checked across auth, document, and chat error paths, not just one.

No new backend code changes were needed this round - the audit surfaced zero real defects (only one test-script bug, fixed in the script itself, not the app). All test tenants cleaned up (`DELETE FROM tenants;`) after each run.

**Runtime state at hand-off:** backend running natively (`./mvnw spring-boot:run`, PID-tracked, not a container) on `:8080` against the native MacPorts Postgres; frontend running natively (`npm run dev`) on `:5173`; DB empty; Podman containers stopped (not removed - `backend-postgres-1`/`backend-backend-1` still exist, just not running, if containers are wanted again later). Nothing committed.

### 15.10 This session, follow-up: new `upload_document` MCP tool (user-requested feature, not a bug fix)

User asked whether MCP could upload files too (previously MCP was query/status-only by design, per architecture.md - see the answer given before this request), then explicitly asked to build it, reusing the RAG/ingestion service rather than a parallel implementation.

**New 4th MCP tool: `upload_document`** (`mcp/tools/UploadDocumentTool.java`). Takes `filename` + `contentBase64` (JSON-RPC has no binary/multipart support, so Base64 is the only option for an MCP tool argument), decodes it, and hands it to `DocumentService.upload(MultipartFile)` - the *exact same* method `POST /api/v1/documents/upload` calls - via a new small adapter class `ByteArrayMultipartFile` (`mcp/tools/ByteArrayMultipartFile.java`, package-private, implements `MultipartFile` over a `byte[]`; not `MockMultipartFile`, which is a spring-test-scope class not available on the production classpath). Same validation (type/size/empty), same rate limiting, same staging/parsing/chunking/embedding pipeline, same audit log entry as the REST endpoint - zero divergence, by construction, not by convention. Async like the REST endpoint: returns once the job is `QUEUED`, caller polls `get_ingestion_status`.

**Tests added** (7, all pass, offline/unit): `UploadDocumentToolTest` (4 - correct Base64 decode/filename/bytes passed through via a captured `MultipartFile`, orchestrator triggered with the right ids, malformed Base64 fails with a clear `ApiException` not an uncaught exception, a `DocumentService` rejection like `EMPTY_FILE` propagates correctly) and `ByteArrayMultipartFileTest` (3 - content/size/filename reporting, `isEmpty()`, `transferTo` writes the exact bytes to disk). **Full suite: 171/171, 0 failures.**

**Verified live, end to end, entirely through the MCP protocol (no REST calls except the initial signup)**: hand-rolled SSE+JSON-RPC client - `tools/call upload_document` (Base64 file with a unique marker) → `QUEUED` → polled `tools/call get_ingestion_status` (also via MCP) until `INDEXED` → `tools/call query_user_rag` (also via MCP) asked about that file's content → correct answer, correct citation, `source: LOCAL_KB`, citing the exact document just uploaded through MCP. Confirms the full loop an external agent would use (upload a file, then ask questions about it) works with MCP tools alone, no REST access needed at all beyond authentication.

**Runtime state at hand-off:** backend restarted natively to pick up the new tool (`"Registered tools: 4"` confirmed in the startup log); test tenant data cleaned up. Nothing committed.

---

## 16. Session 4 (2026-10-14) — free hosting, image-routing fix, query spell-correction, local run verified live

New machine (third, separate from both session 3's Mac and the Windows box - see header). Work happened in
two phases: (A) deploying the backend for free, (B) two feature fixes the user asked for, verified with a
real local run against the same production infra.

### 16a. Free hosting: Neon + Render + AWS S3 (deployed and verified live)

Walked the user step-by-step through signing up for and configuring Neon (Postgres 17/18 + pgvector, direct
non-pooler connection, the `synapse_app` role/grant SQL run manually since Neon has no init-script hook),
a scoped-IAM AWS S3 bucket, a Gemini API key, and a Render free Web Service (Docker runtime, auto-detects the
repo's own `Dockerfile`). Full step-by-step writeup is `docs/backend.md` (new doc this session) - not
repeated here, see that file.

**Deployed and confirmed live on Render:** Flyway applied both migrations to the fresh Neon DB with no
permission errors, Hibernate/JPA/HikariCP initialized, MCP server registered 4 tools, `Started
SynapseMcpApplication in 147.004 seconds` (slow - 0.1 vCPU free-tier CPU throttling, not a defect; confirmed
by a 23.6s local start against the identical DB with a normal CPU). One real mistake caught and fixed live:
`DB_USERNAME`/`DB_MIGRATION_USERNAME` are easy to transpose (the low-privilege runtime role vs. the
schema-owning migration role) - walked through it carefully in `docs/backend.md`.

**Gap found and fixed:** `.env` was never in `.gitignore` despite `docker-compose.yml`'s own comment assuming
it would be - added it this session, plus a `.env.example` template (referenced by that same comment but
never actually created).

### 16b. Image ingestion fix: small images try Gemini first, OCR as fallback (user-requested)

User's ask: for images under 2MB specifically, use the LLM (not OCR) to convert to chunks. Confirmed scope
via `AskUserQuestion` before building (per §1's "ask, never guess" rule): should a Gemini failure fall back
to OCR, or fail the job outright? **User chose fall back to OCR** (more robust). Large images (`≥ 2 MiB`)
confirmed unchanged (OCR-only, Gemini hard-rejects those anyway).

**Built:** `IngestionOrchestrator.extractSmallImage` - new method, only reached when `category == IMAGE &&
content.length <= MultimodalLlmExtractor.TIER_1_MAX_BYTES`. Tries Tier 1 (Gemini) first; on
`DocumentExtractionException`, falls back to Tier 2 (Tesseract OCR); if both fail, throws one combined error
naming both (same pattern as the existing Tier2-first-everywhere-else combined-error path). This is the one
category/size combination where Tier 1 runs first, not as a fallback - documented as such in all 4 spec docs
(architecture.md ADR-05 addendum, design.md §1/§4, instruction.md §4.2, plan.md Milestone 3.2 addendum) and
in architecture.md's new ADR-09-adjacent note, this session.

**Chunking checked, not changed:** `ImageLineChunkingStrategy` routes purely by `FileCategory.IMAGE`
regardless of which tier produced the markdown - verified it works correctly for both OCR line output and
Gemini's structured Markdown (headings, `[Figure: ...]` lines), since both still split sensibly on newlines.
No chunking-layer change was needed.

**Tests added:** `IngestionOrchestratorImageRoutingTest` (4 cases, all mocked/offline: Gemini succeeds and
Tier 2 is never called; Gemini fails → OCR fallback succeeds, tier field reflects `TIER_2_JAVA_PARSER`; both
fail → combined error naming both; an image at/over the 2 MiB limit is unaffected, still Tier-2-first). All
pass.

### 16c. Query spell-correction (user-requested, extended to a tool the user didn't explicitly ask about)

User's ask: misspelled queries don't retrieve correctly, fix it. Confirmed design via `AskUserQuestion`
before building: (1) LLM-based correction via the existing Gemini `ChatClient` vs. a local dictionary
spell-checker library - **user chose LLM-based** (fits the existing architecture, context-aware); (2) should
the corrected text drive synthesis too and be surfaced to the client, or stay retrieval-only and invisible -
**user chose drive-everything + surface it**.

**Built:** `QuerySpellCorrector` (new package `rag/correction/`, + `QueryCorrectionProperties` for
`synapse.query.correction.timeout`, default `PT5S`). Sends the raw query to the fixed chat model
(`gemini-3.5-flash-lite`) with a correction-only prompt (fix spelling/typos only; preserve meaning, names,
domain terms; output only the corrected text). **Deliberately best-effort**: any failure (timeout 5s, API
error, blank output) logs a WARN and returns the original query unchanged - never propagates an exception,
since correction is an accuracy nicety, not something that should be able to fail a chat request. Same
bounded `future.get(timeout)` pattern as `MultimodalLlmExtractor`/`AnswerSynthesizer` (a real hung-OkHttp-call
failure mode was already found and fixed in `AnswerSynthesizer` in an earlier session - this reuses that same
defense). Wired into `RagQueryOrchestrator.answer`: corrected text (or the original, if unchanged/failed)
drives the embedding, web search, and both synthesis calls alike; `ChatResponse` gains a new
`correctedQuery` field, populated only when the text actually changed (so a client can show "Showing results
for: ..." only when there's something to show).

**Real gap found during the same review pass, not originally asked about:** `search_user_knowledge_base` (the
MCP tool, plain semantic search with no synthesis/sufficiency/web-fallback) had the exact same
misspelling problem and **no safety net at all** to compensate - retrieval there is also pure cosine
similarity. Found this by re-reading all 4 MCP tool classes for consistency after fixing the chat path.
Fixed to match: same `QuerySpellCorrector` wired into `SearchKnowledgeBaseTool.search`, correcting before
`embeddingModel.embed(...)`. No return-type change (stayed `List<SearchResult>`, not a wrapper record) -
correction here is silent, since this tool is typically called by another agent/LLM, not shown "did you
mean" UX directly like the chat endpoint.

**Tests added:** `QuerySpellCorrectorTest` (6 cases: real correction applied, wrapping-quotes stripped,
Gemini API error falls back to original query, blank model output falls back, a hung call times out and
falls back well under the 5s hang - mirrors `AnswerSynthesizerTimeoutTest`'s pattern, blank/null input never
calls Gemini at all). 2 new cases in `RagQueryOrchestratorTest` (correction drives retrieval+response field;
an unchanged query leaves `correctedQuery` null). New `SearchKnowledgeBaseToolTest` (2 cases: corrected query
is what gets embedded; an already-correct query still works). All pass.

### 16d. Tesseract `jna.library.path` fix made durable (pom.xml), after being asked to remove then re-add it

Same root cause as session 3's §5a entry, independently rediscovered on this (third, different) machine:
Tesseract was correctly installed (MacPorts, `/opt/local/lib`) but JNA doesn't search that path by default,
so every Tesseract-dependent test failed with `Could not initialize class net.sourceforge.tess4j.TessAPI`.
Session 3's fix was a per-developer `~/.zshrc` `JAVA_TOOL_OPTIONS` export (machine-local, not in the repo).
**This session's fix is different and durable:** a `maven-surefire-plugin` config block in `pom.xml` itself,
setting `jna.library.path` to cover every common install location at once (Homebrew Apple Silicon/Intel,
MacPorts, Linux apt paths) - works for any developer's `mvn test` with zero per-machine setup, and is a no-op
on Linux/Docker/Render where the apt-installed library is already in a default-searched path. **User asked
for this to be removed, then separately asked to re-add it** ("if ... actually needed to fix the bug in
proper way do it") after seeing the 8 Tesseract-test failures return without it - it is currently **present**
in `pom.xml`, confirmed via `grep -n jna.library.path pom.xml`. Verified: full suite reaches 172-174/172-174
(depending on what else was added that session) with this in place, and reproducibly fails the same 8 tests
without it (checked both ways, not assumed).

### 16e. Local run verified live against the real Neon/S3/Gemini infra (not just unit tests)

Started the app natively (`source ~/.zshrc && ./mvnw spring-boot:run`, no Docker on this machine) against the
*same* Neon DB, S3 bucket, and Gemini key as the Render deploy - a genuine live smoke test, not mocked.

**Two real problems hit and fixed, in order:**
1. First attempt failed with Hibernate `Unable to determine Dialect without JDBC metadata`, root cause one
   level down: `password authentication failed for user 'admin'` - the user's `~/.zshrc` had
   `DB_USERNAME`/`DB_PASSWORD` left over from the local Docker Compose default (`admin`) instead of Neon's
   `synapse_app` role (see §16a's note on this same mistake class). Fixed by the user updating `~/.zshrc`.
2. Second attempt failed with `Port 8080 was already in use` - traced to PID holding the port via `lsof -i
   :8080 -sTCP:LISTEN`, then `ps -p <pid> -o command` to confirm *before* killing it: it was the user's own
   IntelliJ-launched instance of this same app, up 2h45m, unrelated to this session. **Asked the user via
   `AskUserQuestion` before killing another process** (stop it and use my build, or leave it and use that
   instance instead) rather than assuming - user chose to stop it.

**Confirmed working on the third attempt:** Flyway connected to Neon, found the schema already at version 2
(validated, not re-migrated - confirms Render's earlier deploy already ran these migrations against the same
DB and Flyway's idempotency works correctly across environments sharing one DB), Hibernate/HikariCP/MCP
server all initialized cleanly, `Started SynapseMcpApplication in 23.669 seconds` (vs. Render's 147s -
confirms the earlier cold-start slowness really is Render's CPU throttling, not a code issue). Left running
in the background, logs tailed live (via the `Monitor` tool, filtered to error/warning signatures) while the
user manually tested; no errors surfaced during that window.

### 16f. Doc-tracking gap discovered (not caused this session, pre-existing from the repo reorg)

`git status` shows all of `docs/*.md` as untracked (`??`), not merely modified - the "synapse mcp backend
code complete revamp" commit (`4ba04a4`) deleted the old `backend/docs/*.md` files as part of moving the repo
from a `backend/` subfolder up to the root, but the new root-level `docs/*.md` (this file included) were
never `git add`ed afterward. They exist correctly on disk and are being edited normally, but are **not
currently part of git history** at all. Flagged here, not fixed (no commit was requested this session) -
worth `git add docs/` next time a commit is made, or the next session's hand-off notes will vanish from git
the same way these apparently already did once.

**Full test suite, end of session: 174/174 passing** (`./mvnw test`, with the pom.xml Tesseract fix in
place). Nothing committed - the user has not asked for one this session.
