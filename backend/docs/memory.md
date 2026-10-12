# SynapseMCP — Session Memory & Handoff

> **Purpose:** read this first at the start of the next session. It records what is built, what was verified (and how),
> every decision the user made, the environment quirks, and exactly where to resume.
> **Last updated:** 2026-10-13 · **Branch:** `develop-stage` · **Nothing is committed** (everything is untracked; the user has not asked for a commit).
> **No secrets are stored here.** Variable *names* and *where they live* only.

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
| **3.4 Worker + SSE + purge** | **NOT STARTED — next** | see §12 |
| 2.x React UI, 4.x chunking/embeddings, 5.x query routing, 6.x MCP, 7.x chat UI, 8.x tests/docker | Not started | |

**Nothing calls the extractors yet.** After upload, documents stay `QUEUED`/`PENDING`; the S3 object stays staged until M3.4's worker exists.

---

## 4. Decisions made by the user (do not re-ask)

**Tiering / extraction**
- Under or equal to **2 MiB (2×1024×1024 bytes)**: only **PDFs and images** go to Gemini (Tier 1). **Everything else uses Java parsers at any size** (txt/json/csv/md, HTML, Word, Excel, PowerPoint). Spec says "< 2MB"; user accepted "≤ 2 MiB". Over 2 MiB: Java parsers for all types incl. PDF and images. Over 10 MiB: 413 `FILE_LIMIT_EXCEEDED`.
- **Gemini failure in Tier 1 = fail the job.** No retry, no fallback parser. (Implemented — see the SDK-retry gotcha in §7.)
- **OCR (images >2 MiB):** Tess4J with bundled Tesseract natives + bundled English data (`src/main/resources/tessdata/eng.traineddata`, ~4 MB, tessdata_fast). Chosen over Tika + system Tesseract. Class is `TesseractOcrDocumentParser` (spec calls it `TikaOcrDocumentParser`).
- **Embeddings get their own retries:** 3 attempts, exponential backoff, transient errors only (429/5xx). Tier 1 chat extraction stays at 1 attempt. Not built yet (M3.4/4.2).

**Models**
- Chat model is **`gemini-3.5-flash`** (user asked to leave `gemini-3.8-flash`; their requested "gemini-2.1-flash" does not exist). Specs still say `gemini-3.8-flash` and were **not** edited. Embedding model **`gemini-embedding-2`**, 768 dims (verified).

**Infra**
- AWS S3, bucket **`aws-rag-bucket-app`**, region **us-east-1**. Credentials read **explicitly** from `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` (non-standard names the user chose). Optional `S3_ENDPOINT` (blank = real AWS; set for LocalStack/MinIO → path-style).
- JWT HS256, 24h, claims sub/tenantId/email/role; tenant name = user's full name; audit_logs has RLS; no FORCE RLS; app connects as a **non-owner role** so RLS applies; MCP key lookup via SECURITY DEFINER function; CORS default `http://localhost:5173`.
- MCP via Spring AI MCP Server starter (WebMVC SSE), currently **disabled** (`spring.ai.mcp.server.enabled=false`); must be remapped to `/mcp/sse` in Phase 6.

**Spec conflicts resolved:** instruction.md's fallback message differs from the others; use architecture.md/plan.md/design.md's: *"No relevant data found in your knowledge base, and fallback web search returned no matching results."*

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

## 6. Repository layout (backend/)

```
src/main/java/com/synapsemcp/
  auth/ (controller, dto, entity, repository, service)     signup/signin, provisioning
  security/  TenantContext, TenantAwareTaskDecorator, TenantRlsTransactionManager, RlsSupport,
             JwtTokenProvider, JwtAuthenticationFilter, McpApiKeyAuthenticationFilter, BearerTokenExtractor
  config/    SecurityConfig, S3Config, StorageProperties, SpringAiConfig, PersistenceConfig, VirtualThreadConfig
  document/  controller (upload only), service (DocumentService, DocumentStorageService), entity, repository, dto, model (FileCategory)
  ingestion/ entity (IngestionJob, JobStatus, JobStage), repository
    extraction/  IngestionTierRouter, MultimodalLlmExtractor (Tier 1), JavaParserExtractor (Tier 2 router),
                 DocumentParserStrategy, ExtractionRequest/Result/Properties, DocumentExtractionException
    extraction/parser/  PdfBox, PoiWord, PoiExcel, PoiPpt, JsoupHtml, Text, TesseractOcr parsers + MarkdownSupport
  rag/entity (Chunk)   mcp/ (entity ApiKey, ApiKeyAuthenticator/Hasher)   common/ (exception handler, AuditLog entity)
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
| `.\mvnw.cmd test "-Dtest=*Test"` | all 69 offline unit tests (incl. real Office fixtures, real Tesseract OCR, mocked Gemini) | **69 pass** |
| `... -Dtest=S3LiveIT` | real S3 round trip | pass |
| `... -Dtest=GeminiLiveIT` | real chat + 768-dim embedding via Spring AI beans | pass |
| `... -Dtest=MultimodalExtractorLiveIT` | real PDF/PNG/corrupt-PDF extraction | passed earlier on 3.5-flash; **2 tests blocked by the 429 quota on 2026-10-13** |
| `... -Dtest=MultimodalExtractorDeepLiveIT` | multi-page, table, scanned PDF, JPEG, WebP | passed on 3.5-flash |
| `... -Dtest='MultimodalExtractorLimitsLiveIT*'` | real truncation failure + 20-page timing (20-page dense = 518s on 3.5) | passed; **edited afterwards, not re-run** |

Live `*IT` classes are opt-in (skipped without the env var) and are **not** in the default `*Test` pattern. Each live Gemini call consumes free-tier quota — be sparing.

**HTTP regression matrix (19 checks, all passed on 2026-10-13 against the real app + Postgres):** signup 201, duplicate email (case-insensitive) 409, bad email/short password 400, signin 200, wrong password/unknown email 401, upload without/with bad token 401, unsupported type 415, empty file 400, >10 MB 413 with `FILE_LIMIT_EXCEEDED` + exact message, unknown path 404 (not 401), `/mcp` without/with bad key 401. The script (`matrix.ps1`) lived in a session scratch folder that will not survive; **recreate it or ask to have it saved into `docs/`**. It never sends a valid file, so it never writes to S3. Delete its throwaway `audit-*@example.com` account afterwards.

**RLS isolation (re-verified as the real `synapse_app` role):** unbound = 0 rows; bound sees only own rows; cross-tenant read 0; cross-tenant INSERT/UPDATE-tenant_id rejected; cross-tenant UPDATE/DELETE touch 0; rebind to other tenant sees only theirs; bogus tenant sees nothing. `users` and `tenants` have no RLS by design (sign-in looks up by email across tenants).

**Office fixtures** were generated by driving real Word/Excel/PowerPoint through COM from a PowerShell script (also lost with the scratch folder). Quirks learned: use **English style names** (`'Heading 1'`, `'List Bullet'`), assign Excel cells with `.Formula = [string]…` (not `.Value2`), call `RemoveDocumentInformation(99)` so no author metadata is embedded, kill leftover `WINWORD/EXCEL/POWERPNT` processes after a failed run.

---

## 11. Open decisions / pending

1. **Tier 1 timeout** (`PT90S` now) — recommended `PT10M`; awaiting the user.
2. **Sequencing of chunking/embedding vs M3.4.** The plan puts the worker (3.4) before `DynamicChunker` (4.1) and embeddings (4.2), yet 3.4's text says "purge immediately after embedding batch insert". **Ask the user:** (a) build the worker with the SSE stages + purge and stub chunk/embed, or (b) implement a basic chunker + embedding inside 3.4 so a document really goes QUEUED → INDEXED.
3. Retest after the free-tier quotas reset (Google said ~5h for `3.8-flash` when it was exhausted on 2026-10-13; `3.5-flash` was exhausted later that day, so allow up to ~24h): the 40-page dense PDF, `MultimodalExtractorLimitsLiveIT`, `MultimodalExtractorLiveIT`; optionally compare `gemini-3.6-flash` speed.
4. Confirm the bucket's 24h lifecycle rule exists.
5. Billing for the Gemini key before any real usage.

## 12. Next: Milestone 3.4 — worker, SSE progress, purge (resume here)

Spec: `plan.md` 3.4, `design.md` §4.2 (orchestrator sketch) and §7.2, `architecture.md` §3.2, `instruction.md` §4.3.

**First thing:** verify the `Chunk` entity ↔ `chunks` table on real Postgres (never tested): `vector(768)` mapping via `hibernate-vector`, generated `tsv` column must be read-only (`insertable=false, updatable=false`), `metadata jsonb`, HNSW index present. Insert + read back a chunk as `synapse_app` with a tenant bound.

**Build:**
- `IngestionOrchestrator` with `@Async("ingestionExecutor")` on virtual threads (`TenantAwareTaskDecorator` propagates the tenant; `TenantRlsTransactionManager` sets `app.current_tenant_id` per transaction). Trigger it from `DocumentService.upload` **after commit** (today upload leaves jobs `QUEUED`).
- Load the staged bytes (`DocumentStorageService.loadStagedFile`), build `ExtractionRequest(filename, FileCategory.resolve(name).category(), contentType, bytes)`, route with `IngestionTierRouter` → `MultimodalLlmExtractor` or `JavaParserExtractor`.
- Progress/stages exactly as the DDL constraints allow — `ingestion_jobs.status` ∈ {QUEUED, PARSING, CHUNKING, EMBEDDING, PURGING_S3, COMPLETED, FAILED}; `stage` ∈ {QUEUED, EXTRACTION, SPLITTING, VECTORIZING, PURGING_S3, COMPLETED, FAILED}; percents 5/25/50/75/90/100; `documents.status` ∈ {PENDING, PROCESSING, INDEXED, FAILED, REJECTED}.
- **Purge must run on failure too** (try/finally), setting `documents.storage_path = NULL`, `raw_file_purged = TRUE`; failures set `documents.status = FAILED` + `error_message` and the job `FAILED`.
- `IngestionProgressSseService` + `GET /api/v1/documents/{id}/progress-stream` (`SseEmitter`), events `progress` with `{documentId, stage, progressPercent, status}`. Note: browsers' `EventSource` cannot send headers; `design.md` passes `?token=<jwt>`. **Already supported:** `JwtAuthenticationFilter.resolveToken` falls back to `BearerTokenExtractor.fromQueryParam` when the URI matches its `PROGRESS_STREAM` pattern (and the MCP filter accepts `?token=` too). It has never been exercised over HTTP because the endpoint doesn't exist yet — confirm the URI pattern matches `/api/v1/documents/{uuid}/progress-stream` and that the query token is accepted **only** there. Also add the polling fallback `GET /api/v1/documents/{id}/status` (plan risk table), `GET /api/v1/documents`, `DELETE /api/v1/documents/{id}` (design §7.2).
- Bound Tier 2 parsing (timeout/size) and decide the Tier 1 timeout (§11.1).
- Embeddings: Spring AI `EmbeddingModel` (768d) with **3 attempts + backoff on 429/5xx** (separate embedding client; see §7). Quota for the embedding model is unknown.
- Tests: end-to-end with real Postgres + real S3 (upload → INDEXED → S3 object gone → chunks exist, RLS-isolated), SSE stage order, failure path purges, then the 19-check HTTP matrix again.

## 13. Not built yet (whole backend)

Chunker (4.1), embeddings persistence (4.2), vector retrieval + sufficiency evaluator (5.1), Tavily fallback (5.2, needs a Tavily key — **not provided yet**), answer synthesis + deterministic failure (5.3), `POST /api/v1/chat`, MCP server + tools + key endpoints `POST/GET/DELETE /api/v1/keys/mcp` (6.x), rate limiting, audit_logs writes, document list/delete/status endpoints, the React frontend (2.x/7.x), Docker/compose (8.2), `./mvnw clean verify` full suite (8.1).

## 14. Lessons / gotchas (so they are not rediscovered)

- A passing test can hide a wrong premise — real files exposed 3 bugs the mocks could not (Word style-based bullets, legacy `.ppt` tables, Excel date format). Prefer real fixtures.
- "Listed" ≠ usable (Gemini model list); "set" ≠ in effect (retry property; SDK retries below Spring AI); measure instead of assuming.
- The `Edit` tool needs exact text; invisible characters (e.g. NBSP) can break matches — rewrite the line by number via PowerShell.
- Minimal Spring test contexts don't resolve `@Value` placeholders; bind through typed properties.
- Background tests: run with `run_in_background`, wait for the notification, then read the log; a long-document live test can run ~9 minutes.
- Clean up after yourself: throwaway tenants/accounts, temp probe tests (`Tmp*`), scratch PDFs.
- Don't print or log secrets; check variables by `[bool]`/length only.

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

### 15.3 Runtime/data state at hand-off
DB has no tenants/users/documents/chunks. The S3 bucket should contain nothing from us (live test deletes its object). Local servers: none left running (port 8080 free). Temp probes (`Tmp*`) and scratch fixtures removed from the repo.
