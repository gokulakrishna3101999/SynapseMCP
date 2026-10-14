# SynapseMCP — Full Implementation Reference (as built)

> **Document type:** consolidated as-built reference, assembled from `architecture.md`, `design.md`,
> `instruction.md`, `plan.md`, `backend.md`, and `memory.md`. Those five remain the source of truth for
> *why* each as-built deviation happened (ADRs, user decisions, real bugs found and fixed) and for the
> original spec baseline; this document is the single place to read **what exists right now**, without
> cross-referencing every "as built" callout scattered across them. When the two disagree, the other docs
> win - update this file to match, don't edit around it.
>
> **Last assembled:** 2026-10-14, after session 4 (image-routing fix, query spell-correction, free hosting
> on Neon + Render, local run verified live). See `memory.md` §16 for that session's full account.

---

## 1. What this system is

SynapseMCP is a multi-tenant Retrieval-Augmented Generation (RAG) platform. Every registered user is an
isolated tenant with exactly **one** knowledge base. Users upload documents (7 file categories), the backend
extracts, chunks, and embeds their content into PostgreSQL/pgvector, and both a REST chat endpoint and an
embedded MCP server let the user (or an external LLM client holding their API key) ask questions grounded in
that knowledge base, falling back to a web search and then a deterministic failure message when local
context is insufficient.

Core invariants, all still true as built:
- **One knowledge base per tenant**, auto-provisioned at signup. No KB management UI/API.
- **Fixed AI models**, not user-configurable: chat/extraction model `gemini-3.5-flash-lite`, embedding model
  `gemini-embedding-2` (768 dimensions). (Originally specced as `gemini-3.8-flash`; switched after repeatedly
  exhausting that model's free-tier quota during development - see §9.)
- **Zero permanent object storage.** Uploaded files are staged in S3 only for the duration of ingestion, then
  permanently deleted the moment their chunks+embeddings are committed to Postgres. No raw-file re-download.
- **Transient chat history.** Conversation turns live in the client only; the server never persists a
  transcript. A client may optionally send its own recent history with a chat request for continuity.
- **Two consumption surfaces**: a REST API (for a web client) and an embedded MCP server (for external LLM
  tools), sharing the same underlying services so neither ever diverges in behavior.

---

## 2. Technology stack (as built)

| Layer | Technology |
|---|---|
| Backend runtime | Java 21 (virtual threads), Spring Boot 3.5.15 |
| AI integration | Spring AI 1.1.7, Google GenAI starters (chat + embedding) |
| Database | PostgreSQL 17/18 + pgvector (HNSW cosine index, `vector(768)`), Flyway migrations |
| Object storage | Real AWS S3 (SDK 2.55.8), transient staging only - no MinIO/LocalStack in any deployed environment |
| Auth | Nimbus JWT (HS256, 24h) for the web client; SHA-256-hashed API keys for MCP clients |
| Document parsing | Apache PDFBox, Apache POI (Word/Excel/PowerPoint), Jsoup (HTML), a custom UTF-8/Windows-1252 text reader, Tess4J/Tesseract (OCR) |
| MCP transport | Spring AI MCP Server starter (`spring-ai-starter-mcp-server-webmvc`), SSE at `/mcp/sse` + `/mcp/messages` |
| Fallback web search | DuckDuckGo Instant Answer API (no key; originally specced as Tavily) |
| Containerization | Multi-stage `Dockerfile` (Maven build stage + `eclipse-temurin:21-jre` runtime with `tesseract-ocr` installed via apt) |
| Local orchestration | `docker-compose.yml` (pgvector/pgvector Postgres image + the backend image) - optional, not required for local dev (see §10) |
| Hosting (free tier) | [Neon](https://neon.tech) (Postgres+pgvector) + [Render](https://render.com) (Docker web service) + real AWS S3 |

---

## 3. Package layout (`src/main/java/com/synapsemcp/`)

```
auth/            signup/signin, JWT issuance, tenant+KB auto-provisioning
security/        TenantContext (ThreadLocal<UUID>), TenantAwareTaskDecorator (propagates it onto async
                 virtual threads), TenantRlsTransactionManager + RlsSupport (binds app.current_tenant_id
                 per-transaction, not via an HTTP interceptor), JwtTokenProvider/JwtAuthenticationFilter,
                 McpApiKeyAuthenticationFilter, BearerTokenExtractor
config/          SecurityConfig, SpringAiConfig (fixed chat/embedding beans, Tier-1-only-1-attempt SDK
                 retry, embedding's own 3-attempt RetryTemplate), S3Config/StorageProperties,
                 VirtualThreadConfig, PersistenceConfig
document/        DocumentController (upload, progress-stream, status, list, delete), DocumentService
                 (upload validation/staging/ownership checks), DocumentStorageService (S3 stage/purge),
                 IngestionProgressSseService (in-memory SseEmitter registry), FileCategory (7 categories,
                 MIME derived from extension, never trusted from the client)
ingestion/
  service/       IngestionOrchestrator - the async pipeline coordinator (see §5)
  chunking/      DynamicChunker (dispatcher) + ExcelChunkingStrategy, SlideChunkingStrategy,
                 HeadingAwareChunkingStrategy, ImageLineChunkingStrategy, shared ChunkingSupport
  extraction/    JavaParserExtractor (Tier 2 router across the 7 DocumentParserStrategy impls),
                 MultimodalLlmExtractor (Tier 1, Gemini), ExtractionProperties/Tier2ExtractionProperties
  extraction/parser/  PdfBoxDocumentParser, PoiWordDocumentParser, PoiExcelDocumentParser,
                      PoiPptDocumentParser, JsoupHtmlDocumentParser, TextDocumentParser,
                      TesseractOcrDocumentParser + shared TesseractOcrEngine, MarkdownSupport
rag/
  controller/    ChatController (POST /api/v1/chat)
  service/       RagQueryOrchestrator - the 3-stage query pipeline (see §6)
  correction/    QuerySpellCorrector + QueryCorrectionProperties (new this session - see §6.1)
  retrieval/     VectorRetriever, ChunkMatch, VectorLiteral
  evaluator/     SufficiencyEvaluator (0.70 cosine threshold)
  synthesis/     AnswerSynthesizer (+ ChatSynthesisProperties)
  websearch/     WebSearchService interface, DuckDuckGoWebSearchService impl
  repository/    ChunkRepository (native HNSW query)
mcp/
  tools/         SearchKnowledgeBaseTool, QueryRagTool, GetIngestionStatusTool, UploadDocumentTool
                 (+ ByteArrayMultipartFile adapter so the MCP upload reuses DocumentService.upload directly)
  service/       McpKeyService, ApiKeyAuthenticator, ApiKeyHasher
  controller/    McpKeyController (generate/list/revoke keys)
common/          GlobalExceptionHandler (RFC 7807 ProblemDetail), RateLimiter, AuditLogService
```

No hand-written MCP protocol handler or SSE endpoint class exists - the Spring AI MCP Server starter provides
JSON-RPC 2.0 handling and the SSE transport; `@McpTool`-annotated methods on the 4 tool classes are all that's
registered.

---

## 4. Database schema

PostgreSQL 17+ with `vector` and `uuid-ossp` extensions. Core tables: `tenants`, `users`, `api_keys`,
`knowledge_bases` (unique-constrained to exactly one per tenant), `documents`, `ingestion_jobs`, `chunks`
(`embedding vector(768)`, HNSW cosine index, generated `tsv` column present in the schema but not currently
wired into any query path - retrieval is pure vector similarity, no hybrid lexical search is active),
`audit_logs`. Full DDL lives in `architecture.md` §4.2 and the Flyway migrations
(`src/main/resources/db/migration/V1__init_schema.sql`, `V2__rls_audit_and_api_key_auth.sql`).

**Tenant isolation, two layers:**
1. Application: every repository query explicitly filters by `tenant_id`.
2. Database: Row-Level Security policies on `documents`, `chunks`, `ingestion_jobs`, `api_keys`,
   `knowledge_bases`, keyed off the `app.current_tenant_id` session variable, bound per-transaction by
   `TenantRlsTransactionManager`. The app connects as a non-owner role (`synapse_app`) so these policies
   actually apply - Flyway runs migrations as the schema-owning role, which does not have RLS enforced
   against it.

---

## 5. Ingestion pipeline (upload → indexed)

1. **Upload**: `POST /api/v1/documents/upload` (or the `upload_document` MCP tool, Base64-encoded). Validates
   size (≤10MB, else HTTP 413 `FILE_LIMIT_EXCEEDED`), non-empty, and resolves `FileCategory` from the file
   **extension** (never the client-supplied Content-Type header). Stages the raw bytes to S3
   (`tenants/{tenantId}/staging/{docId}/{filename}`), creates `Document`/`IngestionJob` rows, returns 202.
2. **Async extraction** (`IngestionOrchestrator.processDocumentAsync`, on a virtual thread with the uploading
   tenant bound for RLS):
   - **Every category except small images**: the matching `DocumentParserStrategy` (Tier 2, Java/Apache
     libraries, free, no external API call) is tried first. On failure, if the file is `≤ 2 MiB`,
     `MultimodalLlmExtractor` (Tier 1, Gemini) is tried as a fallback. If that also fails, or the file is
     too large for Tier 1, the job fails with a combined error naming both failures.
   - **Images under 2 MiB** (the one exception, this session's change): the order is reversed.
     `extractSmallImage` tries Gemini (Tier 1) **first**, falling back to Tesseract OCR (Tier 2) only if
     Gemini fails or times out. Rationale: Tesseract reads clean text scans well but does poorly on stylized
     text, low-contrast scans, or images that are mostly visual with little text; Gemini's multimodal model
     handles both in one pass. Images `≥ 2 MiB` are unaffected (OCR-only, since Gemini hard-rejects anything
     over 2 MiB regardless).
   - PDFs get a per-page OCR fallback for pages with no embedded text layer (same Tesseract engine), so a
     mixed scanned/text PDF only pays OCR cost on the pages that actually need it.
3. **Chunking** (`DynamicChunker`, dispatches by `FileCategory`):
   - **Excel**: one chunk per ~256 words, split only on table-row boundaries; the sheet name + header row
     are re-injected into every chunk, including continuations.
   - **PowerPoint**: exactly one chunk per slide (title + bullets + table + speaker notes), never split or
     merged.
   - **Word / PDF / HTML / Text**: `HeadingAwareChunkingStrategy` - splits on H1/H2 Markdown headings (real
     ones for Word/HTML; none exist for PDF's Tier 2 output, which degrades to paragraph packing, *except* a
     fact-sheet heuristic that detects many short ALL-CAPS heading-like lines and splits one chunk per fact
     instead of packing to the word target, preventing dilution in documents built as many short independent
     facts).
   - **Images**: `ImageLineChunkingStrategy` - one chunk per non-blank line of the extracted Markdown
     (whether that Markdown came from OCR or Gemini), since images tend to be a handful of independent facts
     rather than flowing prose; packing them toward a word target would dilute each fact's embedding.
4. **Embedding + persistence**: each chunk is embedded via `gemini-embedding-2` (768d, with its own 3-attempt
   retry policy distinct from Tier 1 extraction's fail-fast-no-retry policy) and saved as a `Chunk` row.
5. **Purge**: the staged S3 object is deleted - on both success *and* failure - and `documents.storage_path`
   set to `NULL`, `raw_file_purged = TRUE`. Progress is broadcast throughout via SSE
   (`GET /api/v1/documents/{id}/progress-stream`): `QUEUED → PARSING → CHUNKING → EMBEDDING → PURGING_S3 →
   COMPLETED`.

Supported categories/extensions: PDF (`.pdf`), Word (`.doc`/`.docx`), Excel (`.xls`/`.xlsx`), PowerPoint
(`.ppt`/`.pptx`), HTML (`.html`/`.htm`), Text (`.txt`/`.csv`/`.md`/`.json`), Images
(`.png`/`.jpg`/`.jpeg`/`.webp`).

---

## 6. RAG query pipeline

`RagQueryOrchestrator.answer(tenantId, query, history)` - shared by `POST /api/v1/chat` and the
`query_user_rag` MCP tool:

### 6.1 Spell-correction (new this session)
Before anything else, the raw query is passed through `QuerySpellCorrector`: one best-effort Gemini call that
corrects spelling/typos only (preserves meaning, names, domain terms), bounded by a 5s timeout. Any failure
(timeout, API error, blank output) silently falls back to the original query - this never fails the request.
Motivated by retrieval being pure cosine similarity with no lexical/keyword fallback, so a misspelled query
can embed too far from the correct one to clear the sufficiency threshold even when the right chunks exist.
The corrected text (when it differs) drives every subsequent step and is surfaced as `ChatResponse.correctedQuery`.
The same correction also runs in the `search_user_knowledge_base` MCP tool (§7), which has no
sufficiency/web-fallback safety net of its own to otherwise compensate for a bad embedding.

### 6.2 Retrieval
The (possibly corrected) query, with the last 2 user turns of client-sent conversation history folded in for
context, is embedded and matched against the tenant's chunks via `VectorRetriever` (`ORDER BY embedding <=>
:queryVector LIMIT 8`, cosine similarity = `1 - distance`), filtered by `tenant_id` alone (sufficient since
each tenant has exactly one KB; RLS is the defense-in-depth layer underneath).

### 6.3 Sufficiency check and routing
`SufficiencyEvaluator`: local context is sufficient if at least one match has similarity `≥ 0.70`.
- **Sufficient** → `AnswerSynthesizer.synthesizeFromLocal` (chunks grouped by source document in the prompt,
  not left in flat rank order - needed for the model to reliably connect a title chunk and its detail chunk
  from the same document). Returns `source: "LOCAL_KB"` with citations.
- **Insufficient** → falls back to `DuckDuckGoWebSearchService` (Instant Answer API - typically zero or one
  abstract-style result, not a ranked multi-result search). Results found → `synthesizeFromWeb`, `source:
  "WEB_SEARCH"`. No results → the deterministic message, `source: "NONE"`:
  `"No relevant data found in your knowledge base, and fallback web search returned no matching results."`

Synthesis always runs at `temperature(0.0)` for strict grounding, with its own 3-attempt retry + a bounded
timeout (a real hung-OkHttp-call failure mode was found and fixed here in an earlier session).

---

## 7. MCP server

Transport: `GET /mcp/sse` (SSE), `POST /mcp/messages` (JSON-RPC 2.0 ingress) - both provided by the Spring AI
MCP Server starter. Auth: `Authorization: Bearer <mcp_api_key>`, validated by `McpApiKeyAuthenticationFilter`
against a SHA-256-hashed key, binding `tenant_id` into `TenantContext` exactly like the JWT filter does for
the web client.

**4 registered tools** (one beyond the original 3-tool spec):
1. `search_user_knowledge_base` - plain semantic search, no synthesis. Spell-corrects the query first (§6.1).
2. `query_user_rag` - the full pipeline in §6, minus conversation history (MCP clients manage their own).
3. `get_ingestion_status` - polls a document's ingestion progress by ID.
4. `upload_document` - Base64-encoded filename+content, reuses `DocumentService.upload` directly via a
   `MultipartFile` adapter, so an MCP upload is identical in every respect (validation, staging, parsing,
   chunking, embedding, rate limiting, audit logging) to the REST upload endpoint. Verified live end-to-end
   entirely through the MCP protocol: upload → poll status → query, with no REST access needed beyond initial
   key generation.

---

## 8. Security

- **Web client**: stateless JWT (HS256, 24h), claims `sub`/`tenantId`/`email`/`role`.
- **MCP client**: `syn_mcp_...` API keys, SHA-256-hashed at rest, revocable, looked up via a
  `SECURITY DEFINER` Postgres function so the lookup itself doesn't need RLS bypassed any other way.
- **Tenant context propagation**: `TenantContext` (`ThreadLocal<UUID>`), explicitly propagated onto async
  ingestion's virtual threads via `TenantAwareTaskDecorator` (plain `ThreadLocal` doesn't cross thread
  boundaries on its own). `TenantRlsTransactionManager` binds `app.current_tenant_id` per-transaction, not
  via an HTTP interceptor - needed because custom/native repository queries (like the HNSW vector search)
  don't get Spring Data's default per-call transaction wrapping.
- **CORS**: configurable allowed-origins list, defaults to `http://localhost:5173`.
- **Rate limiting**: per-tenant, per-operation buckets (`RateLimiter`) on uploads, chat/search, and status
  polling.

---

## 9. AI model configuration (fixed, not user-selectable)

- **Chat/extraction**: `gemini-3.5-flash-lite` (originally specced `gemini-3.8-flash`; switched after
  exhausting that model's and `gemini-3.5-flash`'s free-tier `generateContent` quota - 20 requests/day -
  twice during development). Tier 1 extraction fails the job outright on any error, no retry (the Google SDK
  itself is limited to 1 HTTP attempt via `SpringAiConfig`'s custom `Client` bean, overriding its own default
  5-attempt-with-backoff retry, which was turning a quota error into a 20-45s wait). Answer synthesis and
  query spell-correction, by contrast, do retry transient errors (429/5xx) up to 3 times with backoff, since
  a live user-facing request benefits from that where a background ingestion job doesn't.
- **Embedding**: `gemini-embedding-2`, 768 dimensions, its own dedicated 3-attempt retry policy (a separate
  Spring bean from the chat retry template, since Spring AI's auto-configuration would otherwise have shared
  one global 1-attempt template between both).

---

## 10. Deployment

### 10.1 Free-tier hosting (Neon + Render + AWS S3)
Full step-by-step guide: `docs/backend.md`. Summary: Neon (serverless Postgres + pgvector, direct non-pooler
connection, `synapse_app` runtime role created manually via Neon's SQL editor since there's no init-script
hook) + Render (free Docker web service, builds the repo's own `Dockerfile` - needed because the runtime
image installs `tesseract-ocr` via `apt-get`, which buildpack-only hosts can't do) + real AWS S3 (scoped IAM
user, bucket-only inline policy). Verified live: Flyway migrates cleanly, all 4 MCP tools register, cold
start ≈147s (Render's 0.1 vCPU free-tier CPU throttling, not a code defect - confirmed by a 23.7s local start
against the identical DB with normal CPU).

### 10.2 Local development (no Docker required)
`.env.example` at the repo root documents the 11-12 required environment variables. `.env` itself is
git-ignored. Spring Boot does not auto-load `.env` files, so it must be sourced in the same command that
starts the app (`source ~/.zshrc && ./mvnw spring-boot:run`, or `source .env && ...`), not assumed to be
picked up automatically. A `maven-surefire-plugin` block in `pom.xml` sets `jna.library.path` to cover every
common Tesseract install location (Homebrew both architectures, MacPorts, Linux apt paths) so
Tesseract-dependent tests pass on any developer's machine without manual setup - a no-op on
Linux/Docker/Render, where the library is already in a default-searched path.

### 10.3 Docker Compose (local containers, alternative to §10.2)
`docker-compose.yml` runs `pgvector/pgvector:pg17` (no host port published - avoids clashing with a local
Postgres on 5432) + the backend's own multi-stage `Dockerfile`. `docker/init-db.sh` creates the
`synapse_app` role with the same default-privilege grants Neon's setup replicates manually. Not exercised in
session 4 (no Docker on that machine); last verified in an earlier session.

---

## 11. Testing

**Full suite: 174/174 passing** (`./mvnw test`, as of session 4, with the pom.xml Tesseract fix in place).
Breakdown by category:
- **Parser edge cases** (`ParserEdgeCaseStabilityTest`): corrupt/empty/malformed input for all 7 categories -
  every case must surface as a clean `DocumentExtractionException`, never an uncaught exception or hang.
- **Per-format parser tests** (`OfficeParsersTest`, `TextAndHtmlParsersTest`, `TesseractOcrParserTest`,
  `JavaParserExtractorTest`): real-library-generated fixtures (not hand-crafted bytes) for Word/Excel/PPT,
  real Office-authored fixtures for legacy formats, real OCR against real images.
- **Chunking** (`ExcelChunkingStrategyTest`, `SlideChunkingStrategyTest`, `HeadingAwareChunkingStrategyTest`,
  `ImageLineChunkingStrategyTest`, `DynamicChunkerTest`): fixtures matching each real parser's exact Markdown
  output shape.
- **Orchestration** (`IngestionOrchestratorFallbackTest`, `IngestionOrchestratorImageRoutingTest`,
  `IngestionOrchestratorTier2TimeoutTest`): tier-routing decisions and timeout handling, fully mocked.
- **RAG** (`RagQueryOrchestratorTest`, `SufficiencyEvaluatorTest`, `AnswerSynthesizerTimeoutTest`,
  `DuckDuckGoWebSearchServiceTest`, `QuerySpellCorrectorTest`): routing branches, timeout/retry behavior,
  spell-correction.
- **MCP tools** (`UploadDocumentToolTest`, `SearchKnowledgeBaseToolTest`): tool-level behavior, mocked
  dependencies.
- **Live integration** (`*LiveIT`, `*IT` suffix - not in the default `mvn test` pattern, gated behind
  `@EnabledIfEnvironmentVariable`, need real DB/S3/Gemini credentials): `S3LiveIT`, `GeminiLiveIT`,
  `MultimodalExtractorLiveIT`/`DeepLiveIT`/`LimitsLiveIT`, `ChunkRepositoryIT`, `VectorRetrieverIT`,
  `IngestionOrchestratorIT`, `RagQueryOrchestratorLiveIT`, `McpServerLiveIT`, `AuditLogServiceIT`. Each
  consumes real Gemini free-tier quota - run sparingly, and only where credentials are configured (not
  runnable in every environment).

**Not covered by automated tests** (acknowledged gaps, not defects): no test chunks *real* parser output for
every category end-to-end (fixtures match the documented shape, not literally generated by the real parser
in every chunking test); the 24-hour S3 lifecycle expiry rule's existence is not verified by any test (it's
infra config, not app behavior); the React frontend (a separate project, not in this repo) is untested here.

---

## 12. Known limitations (acknowledged, not defects)

- Legacy `.ppt` tables extract as plain lines (the format has no table object).
- Word headers/footers/footnotes/text boxes are not extracted.
- Hidden Excel sheets and CSS-hidden HTML content are still included.
- OCR is English-only (bundled `tessdata/eng.traineddata`).
- Ordered-list numbering in extracted Markdown is emitted as repeated `1.` rather than incrementing.
- `chunks.tsv` (full-text search column) exists in the schema but isn't wired into any query path - retrieval
  is pure vector similarity, not hybrid lexical+vector.
- DuckDuckGo's Instant Answer API is not a ranked multi-result search; more queries land on the deterministic
  `NONE` fallback than a true search API (the originally-specced Tavily) would produce.
- Render's free tier sleeps after 15 minutes idle (cold start ≈147s) and will drop a long-lived `/mcp/sse`
  connection on spin-down - acceptable for personal/demo use, not for continuous MCP client connections
  without upgrading the hosting tier.
- `docs/*.md` (including this file) are currently untracked in git, a side effect of an earlier repo
  reorganization commit that deleted the old `backend/docs/*.md` without re-adding the new root-level
  versions (see `memory.md` §16f) - not re-added here since no commit was requested this session.
