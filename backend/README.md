# SynapseMCP backend

Multi-tenant RAG service exposed over MCP. Java 21, Spring Boot 3.5, Spring AI, PostgreSQL 17 + pgvector, AWS S3 (transient staging only).

Specs live at the repo root: `architecture.md`, `design.md`, `instruction.md`, `plan.md`.

## Prerequisites
- JDK 21
- PostgreSQL 17 with pgvector 0.8+
- A Gemini API key in `GEMINI_API_KEY` (`GOOGLE_API_KEY` also works)
- An S3 bucket (default `aws-rag-bucket-app`, region us-east-1) and credentials in `AWS_ACCESS_KEY` / `AWS_SECRET_KEY`

## Setup
1. Create the database and the non-owner app role: `docs/local-db-setup.sql`.
2. Set the environment variables listed in `.env.example`.
3. Configure the S3 bucket: `docs/s3-setup.md`.
4. Run (Flyway migrates on startup using the `DB_MIGRATION_*` credentials):

```
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
./mvnw test
```

If `JAVA_HOME` is not set, point it at your JDK 21 first.

## Current API
| Method | Path | Auth |
|---|---|---|
| POST | `/api/v1/auth/signup`, `/api/v1/auth/signin` | none |
| POST | `/api/v1/documents/upload` | JWT |

MCP server endpoints are disabled until Phase 6.

## Ingestion tiers
- Up to 2 MiB (2*1024*1024 bytes): PDFs and images go to Gemini; all other types use the Java parsers. A Gemini failure fails the job (no fallback).
- 2 MiB to 10 MiB: Java parsers. Larger uploads are rejected with 413.

### Java parsers (Tier 2), `ingestion/extraction/parser`
| Category | Parser | Output |
|---|---|---|
| PDF | PDFBox | text layer in reading order; scanned PDFs have no OCR and fail with a clear message |
| Word `.doc`/`.docx` | POI | headings by style, lists, tables as Markdown (format detected from content) |
| Excel `.xls`/`.xlsx` | POI | one `## Sheet` section per sheet with a Markdown table; formulas evaluated, values as Excel displays them |
| PowerPoint `.ppt`/`.pptx` | POI | `## Slide N: title`, bullets, tables, speaker notes (`.ppt` tables come out as plain lines) |
| HTML | Jsoup | scripts/styles removed; headings, lists, tables, `pre` kept |
| Text `.txt/.csv/.md/.json` | direct | UTF-8 (BOMs honoured, Windows-1252 fallback) |
| Images | Tess4J (Tesseract) | OCR, English only; the language data is bundled in `src/main/resources/tessdata` |

Run the opt-in live Gemini tests with `GEMINI_API_KEY` set: `./mvnw test -Dtest='MultimodalExtractor*LiveIT,GeminiLiveIT'`.
