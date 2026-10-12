# SynapseMCP — Multi-Tenant RAG System Design Specification

> **Document Type:** System Design Specification (`design.md`)  
> **Role:** Senior Software Architect  
> **Target System:** Enterprise Multi-Tenant RAG Platform with Model Context Protocol (MCP) Server and React Client  
> **Date:** October 2026  
> **Status:** Final Architectural Baseline  

---

## 1. System Overview & Core Principles

SynapseMCP is an enterprise-grade document intelligence platform that bridges proprietary user knowledge with state-of-the-art Large Language Models. 

### Core Architectural Invariants:
1. **Strictly ONE Single Unified Knowledge Base per User:**
   - Every registered user is a tenant with exactly one consolidated Knowledge Base.
   - All uploaded documents automatically map to this single KB.
   - There are no user capabilities or UI screens to create, configure, or switch between multiple knowledge bases.
2. **Fixed Backend AI Models (`gemini-3.8-flash` & `gemini-embedding-2`):**
   - The LLM and Embedding models are fixed within the backend environment: Google **`gemini-3.8-flash`** (LLM) and Google **`gemini-embedding-2`** (Embeddings at 768 dimensions).
   - Users cannot view, select, or modify model providers or model names.
3. **Streamlined Authentication:**
   - Users can only **Sign-up** and **Sign-in**. No complex organizational management or inviting flows.
4. **Zero Permanent Cloud Object Storage (Transient Staging & Purge):**
   - Uploaded raw files are **NOT stored permanently** in AWS S3 or MinIO.
   - S3/MinIO/Local storage is utilized strictly as a transient staging buffer during ingestion.
   - Once chunks and vector embeddings are generated and persisted into PostgreSQL, the staged binary is **immediately and permanently deleted from S3**. Raw file re-downloads are disabled; users view extracted text via citation cards.
5. **Transient Client-Side Chat History:**
   - Conversations are held in React client session state, keeping the PostgreSQL database lean and maximizing tenant privacy without conversational transcript retention.
6. **Dual-Phase Real-Time Progress Tracking:**
   - The React frontend provides seamless, unbroken visual feedback across two distinct phases:
     - **Phase 1 (Client Upload):** Real-time network upload progress (0% – 100%) showing byte transmission via Axios `onUploadProgress`.
     - **Phase 2 (Server Ingestion & Embedding):** Live progress milestones via Server-Sent Events (SSE: `QUEUED` -> `PARSING` -> `CHUNKING` -> `EMBEDDING` -> `PURGING_S3` -> `INDEXED`).
7. **Bimodal Consumption Interfaces:**
   - **Primary Web Client:** React Single-Page Application (SPA) for document upload, real-time ingestion tracking, and conversational RAG chat with rich citations.
   - **MCP Server Layer:** Spring Boot exposes an integrated Model Context Protocol (MCP) Server over HTTP/SSE, allowing external LLM clients (Claude Desktop, Cursor, external AI agents) to connect using a user API key and query the user's isolated knowledge base.
8. **Size-Driven Ingestion Pipeline (7 Categories):**
   - `< 2MB`: LLM Multimodal / Direct extraction via `gemini-3.8-flash`.
   - `2MB – 10MB`: High-speed in-process Java enterprise parsers (Apache PDFBox, Apache POI, Jsoup, Tika/Tesseract).
   - `> 10MB`: Instant fail-fast rejection with HTTP 413 `FILE_LIMIT_EXCEEDED`.
9. **3-Stage Query Routing & Deterministic Fallback:**
   - Step 1: User's single isolated vector KB search in PostgreSQL `pgvector`.
   - Step 2: Fallback to Tavily Search API if local similarity score `< 0.70` or `0 chunks matched`.
   - Step 3: Return explicit deterministic message if web search also fails.

---

## 2. React Frontend Application Design (`/frontend`)

The frontend is structured as a modern Single-Page Application (SPA) built with React 19, Vite, TypeScript, and modern CSS design tokens. It strictly reflects the single-KB, transient-storage, and dual-phase progress constraints.

### 2.1 Directory Structure
```text
frontend/
├── public/
│   └── favicon.svg
├── src/
│   ├── assets/
│   ├── components/
│   │   ├── common/
│   │   │   ├── Button.tsx
│   │   │   ├── Card.tsx
│   │   │   ├── Modal.tsx
│   │   │   ├── Badge.tsx
│   │   │   └── Toast.tsx
│   │   ├── layout/
│   │   │   ├── AppHeader.tsx            # User profile, logout, MCP Key modal trigger
│   │   │   └── MainLayout.tsx           # Two-pane or tabbed layout (Knowledge Base & Chat)
│   │   ├── auth/
│   │   │   ├── LoginForm.tsx            # Email + Password Sign-in
│   │   │   └── SignupForm.tsx           # Email + Password + Full Name Sign-up
│   │   ├── documents/
│   │   │   ├── DocumentUploadDropzone.tsx # Drag & Drop for 7 file categories, client 10MB check
│   │   │   ├── FileUploadProgressCard.tsx # Dual-phase progress tracker (Network Upload % + SSE Ingestion Stepper)
│   │   │   ├── IngestionStepper.tsx     # Visual step indicator (Upload -> Parse -> Chunk -> Embed -> Purged -> Ready)
│   │   │   ├── DocumentTable.tsx        # File list, size, category icon, status badges (INDEXED - S3 Purged), delete
│   │   │   └── DocumentFilter.tsx       # Search and filter uploaded documents by type/status
│   │   ├── chat/
│   │   │   ├── ChatWindow.tsx           # Conversational message list and input bar
│   │   │   ├── MessageBubble.tsx        # User and Assistant messages with markdown rendering
│   │   │   ├── CitationCard.tsx         # Expandable chunk citation (doc name, snippet, similarity)
│   │   │   ├── WebSourceBadge.tsx       # External web search attribution badge and link
│   │   │   └── QueryRoutingIndicator.tsx # Visual indicator: "Searching KB..." -> "Web Fallback..."
│   │   └── settings/
│   │       └── McpKeyModal.tsx          # Generate/Copy/Revoke MCP API Key & Claude/Cursor config snippets
│   ├── context/
│   │   ├── AuthContext.tsx              # User state, JWT token, login/logout actions
│   │   └── ToastContext.tsx             # Global notification toast messages
│   ├── hooks/
│   │   ├── useDocuments.ts              # Fetch document list, trigger upload, delete
│   │   ├── useFileUploadWithProgress.ts # Manages Axios network upload % and transitions to SSE ingestion stream
│   │   ├── useRagChat.ts                # Send query, handle SSE streaming response, manage in-memory session chat
│   │   └── useMcpKeys.ts                # Fetch, generate, and revoke MCP API keys
│   ├── services/
│   │   ├── api.ts                       # Axios client with JWT interceptor & 401 redirect
│   │   ├── authService.ts               # POST /auth/signup, POST /auth/signin
│   │   ├── documentService.ts           # Upload multipart, get documents, get progress-stream, delete
│   │   ├── chatService.ts               # POST /chat (SSE stream or JSON)
│   │   └── mcpKeyService.ts             # GET /keys/mcp, POST /keys/mcp, DELETE /keys/mcp/{id}
│   ├── types/
│   │   ├── auth.ts
│   │   ├── document.ts
│   │   ├── chat.ts
│   │   └── mcp.ts
│   ├── styles/
│   │   ├── index.css                    # Design tokens (dark/light themes, spacing, typography)
│   │   └── components.css
│   ├── App.tsx
│   └── main.tsx
├── package.json
├── tsconfig.json
└── vite.config.ts
```

### 2.2 Component Hierarchy & Dual-Phase Progress UI

```mermaid
graph TD
    App[App.tsx] --> AuthProvider[AuthContext.Provider]
    AuthProvider --> ToastProvider[ToastContext.Provider]
    
    ToastProvider --> AuthGate{User Authenticated?}
    AuthGate -->|No| AuthView[Auth Screen: LoginForm / SignupForm]
    AuthGate -->|Yes| MainLayout[MainLayout]
    
    MainLayout --> AppHeader[AppHeader: User Name, Logout, 'Connect External LLM' Button]
    MainLayout --> SplitView[Dual Workspace View]
    
    subgraph LeftPane["Unified Knowledge Base Workspace"]
        SplitView --> Dropzone[DocumentUploadDropzone (Drag & Drop, 7 Categories, 10MB Check)]
        SplitView --> ProgressCard[FileUploadProgressCard<br/>• Phase 1: Uploading Bar (Bytes transmitted / Speed)<br/>• Phase 2: IngestionStepper (Live SSE Event Stream)]
        ProgressCard --> Stepper[IngestionStepper<br/>[1. Upload 100%] -> [2. Parse 25%] -> [3. Chunk 50%]<br/>-> [4. Embed 75%] -> [5. S3 Purged 90%] -> [6. Ready 100%]]
        SplitView --> DocTable[DocumentTable (Indexed Documents, Status Badges, Delete)]
    end
    
    subgraph RightPane["Conversational RAG Chat Workspace"]
        SplitView --> ChatWin[ChatWindow]
        ChatWin --> RoutingInd[QueryRoutingIndicator (Live Pipeline Status)]
        ChatWin --> MsgList[MessageBubble List (Transient Session)]
        MsgList --> Citations[CitationCard (Local KB Source Chunks)]
        MsgList --> WebBadges[WebSourceBadge (Tavily Fallback Links)]
        ChatWin --> InputBar[Chat Input Bar & Send Action]
    end
```

### 2.3 Dual-Phase Hook Implementation Blueprint (`useFileUploadWithProgress.ts`)
```typescript
import { useState } from 'react';
import axios from 'axios';

export interface IngestionProgressState {
  documentId: string | null;
  phase: 'IDLE' | 'UPLOADING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  uploadPercent: number; // 0 to 100%
  uploadedBytes: number;
  totalBytes: number;
  stage: 'QUEUED' | 'PARSING' | 'CHUNKING' | 'EMBEDDING' | 'PURGING_S3' | 'COMPLETED' | 'FAILED';
  stagePercent: number; // 0 to 100%
  errorMessage?: string;
}

export function useFileUploadWithProgress() {
  const [progress, setProgress] = useState<IngestionProgressState>({
    documentId: null,
    phase: 'IDLE',
    uploadPercent: 0,
    uploadedBytes: 0,
    totalBytes: 0,
    stage: 'QUEUED',
    stagePercent: 0,
  });

  const uploadAndTrack = async (file: File) => {
    // 1. Client-side size pre-validation
    if (file.size > 10 * 1024 * 1024) {
      throw new Error('File limit exceeded: Maximum allowed size is 10MB.');
    }

    setProgress({
      documentId: null,
      phase: 'UPLOADING',
      uploadPercent: 0,
      uploadedBytes: 0,
      totalBytes: file.size,
      stage: 'QUEUED',
      stagePercent: 0,
    });

    const formData = new FormData();
    formData.append('file', file);

    try {
      // Phase 1: Network Upload with Axios onUploadProgress
      const response = await axios.post('/api/v1/documents/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data',
          Authorization: `Bearer ${localStorage.getItem('token')}`,
        },
        onUploadProgress: (progressEvent) => {
          if (progressEvent.total) {
            const percent = Math.round((progressEvent.loaded * 100) / progressEvent.total);
            setProgress((prev) => ({
              ...prev,
              uploadPercent: percent,
              uploadedBytes: progressEvent.loaded,
            }));
          }
        },
      });

      const { documentId } = response.data;

      // Phase 2: Transition to Server Ingestion & Embedding Stream via SSE
      setProgress((prev) => ({
        ...prev,
        documentId,
        phase: 'PROCESSING',
        uploadPercent: 100,
        stage: 'QUEUED',
        stagePercent: 5,
      }));

      // Connect to Server-Sent Events stream
      const eventSource = new EventSource(
        `/api/v1/documents/${documentId}/progress-stream?token=${localStorage.getItem('token')}`
      );

      eventSource.addEventListener('progress', (event) => {
        const data = JSON.parse(event.data);
        setProgress((prev) => ({
          ...prev,
          stage: data.stage,
          stagePercent: data.progressPercent,
        }));

        if (data.stage === 'COMPLETED') {
          setProgress((prev) => ({
            ...prev,
            phase: 'COMPLETED',
            stage: 'COMPLETED',
            stagePercent: 100,
          }));
          eventSource.close();
        }
      });

      eventSource.addEventListener('error', (err) => {
        eventSource.close();
      });

    } catch (err: any) {
      setProgress((prev) => ({
        ...prev,
        phase: 'FAILED',
        errorMessage: err.response?.data?.detail || err.message,
      }));
    }
  };

  return { progress, uploadAndTrack };
}
```

---

## 3. Spring Boot Backend Architecture & Class Design

The backend is built with Java 21 LTS and Spring Boot 3.3+ / 4.x using clean architecture and Virtual Threads (Project Loom).

### 3.1 Package Organization
```text
com.synapsemcp/
├── SynapseMcpApplication.java
├── config/
│   ├── SecurityConfig.java              # SecurityFilterChain, CORS, stateless session
│   ├── VirtualThreadConfig.java         # Async TaskExecutor configured with Virtual Threads
│   ├── SpringAiConfig.java              # Fixed gemini-3.8-flash and gemini-embedding-2 (768d) beans
│   └── WebMvcConfig.java
├── security/
│   ├── TenantContext.java               # ThreadLocal<UUID> holding active tenant_id
│   ├── JwtTokenProvider.java            # JWT generation, validation, and claim parsing
│   ├── JwtAuthenticationFilter.java     # Intercepts /api/v1/** for React requests
│   ├── McpApiKeyAuthenticationFilter.java # Intercepts /mcp/** for external LLM requests
│   └── TenantRlsInterceptor.java        # Injects SET LOCAL app.current_tenant_id into DB session
├── auth/
│   ├── controller/AuthController.java   # POST /api/v1/auth/signup, POST /api/v1/auth/signin
│   ├── service/AuthService.java         # User creation, password hashing, unified KB auto-creation
│   ├── repository/UserRepository.java
│   └── dto/AuthRequests.java
├── document/
│   ├── controller/DocumentController.java # POST /upload, GET /progress-stream, GET /list, DELETE /{id}
│   ├── service/DocumentService.java     # Upload validation, staging dispatch, delete cascade
│   ├── service/DocumentStorageService.java # S3/MinIO transient staging and purge provider
│   ├── service/IngestionProgressSseService.java # Manages client SseEmitter connections and broadcasts
│   ├── repository/DocumentRepository.java
│   └── entity/Document.java
├── ingestion/
│   ├── service/IngestionOrchestrator.java # Coordinates async pipeline, embedding save, SSE push, and S3 purge
│   ├── router/IngestionTierRouter.java   # Routes to Tier 1 (<2MB) or Tier 2 (2MB-10MB)
│   ├── tier1/
│   │   └── MultimodalLlmExtractor.java  # Fixed gemini-3.8-flash multimodal extraction (<2MB)
│   ├── tier2/
│   │   ├── DocumentParserStrategy.java  # Common parser strategy interface
│   │   ├── PdfBoxDocumentParser.java    # Apache PDFBox implementation (.pdf)
│   │   ├── PoiWordDocumentParser.java   # Apache POI implementation (.doc, .docx)
│   │   ├── PoiExcelDocumentParser.java  # Apache POI implementation (.xls, .xlsx -> Markdown)
│   │   ├── PoiPptDocumentParser.java    # Apache POI implementation (.ppt, .pptx)
│   │   ├── JsoupHtmlDocumentParser.java # Jsoup implementation (.html, .htm)
│   │   ├── TextDocumentParser.java      # Streaming UTF-8 reader (.txt, .csv, .md, .json)
│   │   └── TikaOcrDocumentParser.java   # Apache Tika / Tesseract OCR (.png, .jpg, .webp)
│   ├── chunking/
│   │   ├── DynamicChunker.java          # File-adaptive chunking orchestrator
│   │   └── strategies/                  # ExcelChunker, SlideChunker, LayoutChunker, etc.
│   ├── repository/IngestionJobRepository.java
│   └── entity/IngestionJob.java
├── rag/
│   ├── controller/ChatController.java   # POST /api/v1/chat (SSE / JSON)
│   ├── service/RagQueryOrchestrator.java # Coordinates vector search -> fallback -> synthesis
│   ├── retrieval/VectorRetriever.java   # 768d HNSW vector query against pgvector with tenant_id filter
│   ├── evaluator/SufficiencyEvaluator.java # Score threshold evaluation (< 0.70 cutoff)
│   ├── websearch/
│   │   ├── WebSearchService.java        # Search interface
│   │   └── TavilyWebSearchService.java  # Tavily REST client implementation
│   ├── synthesis/AnswerSynthesizer.java # gemini-3.8-flash synthesis with source citations
│   ├── repository/ChunkRepository.java
│   └── entity/Chunk.java
├── mcp/
│   ├── server/McpServerEndpoint.java    # HTTP/SSE transport controller (/mcp/sse, /mcp/messages)
│   ├── protocol/McpProtocolHandler.java # Handles JSON-RPC 2.0 messages (initialize, tools/list, tools/call)
│   ├── tools/
│   │   ├── SearchKnowledgeBaseTool.java # Tool: search_user_knowledge_base
│   │   ├── QueryRagTool.java            # Tool: query_user_rag
│   │   └── GetIngestionStatusTool.java  # Tool: get_ingestion_status
│   ├── service/McpKeyService.java       # Issues and revokes SHA-256 hashed API keys
│   ├── repository/ApiKeyRepository.java
│   └── entity/ApiKey.java
└── common/
    ├── exception/GlobalExceptionHandler.java # RFC 7807 Problem Detail response formatter
    └── dto/ApiResponse.java
```

---

## 4. Ingestion Pipeline & Parsers Design

### 4.1 Tier Routing Decision Logic
The `IngestionTierRouter` inspects `file.getSize()` and MIME type:

```mermaid
flowchart TD
    File[Incoming Uploaded File] --> SizeCheck{File Size}
    
    SizeCheck -->|> 10 MB| Reject[Reject: HTTP 413 FILE_LIMIT_EXCEEDED]
    
    SizeCheck -->|<= 10 MB| StageS3[Stage binary temporarily in S3/MinIO<br/>Key: tenants/:tenantId/staging/:docId/:filename]
    
    StageS3 --> SplitTier{File Size Tier}
    
    SplitTier -->|< 2 MB| Tier1[Tier 1: Multimodal LLM Extraction]
    Tier1 --> LLMCall[Call Fixed gemini-3.8-flash Multimodal API<br/>• Sends raw binary / base64<br/>• Extracts high-fidelity Markdown, tables & OCR]
    LLMCall --> StructuredText[Standardized Markdown Text]
    
    SplitTier -->|2 MB – 10 MB| Tier2[Tier 2: Java Library Parsing Engine]
    Tier2 --> TypeDetect{Format Category}
    
    TypeDetect -->|PDF| P1[PdfBoxDocumentParser: Text, layout & bookmarks]
    TypeDetect -->|Word| P2[PoiWordDocumentParser: Headings, paragraphs, tables]
    TypeDetect -->|Excel| P3[PoiExcelDocumentParser: Sheets transformed to Markdown tables]
    TypeDetect -->|PowerPoint| P4[PoiPptDocumentParser: Slide-by-slide text and notes]
    TypeDetect -->|HTML| P5[JsoupHtmlDocumentParser: Clean DOM, semantic tags]
    TypeDetect -->|Text/CSV/JSON/MD| P6[TextDocumentParser: Streamed UTF-8 lines]
    TypeDetect -->|Images| P7[TikaOcrDocumentParser: Tika / Tesseract OCR]
    
    P1 --> StructuredText
    P2 --> StructuredText
    P3 --> StructuredText
    P4 --> StructuredText
    P5 --> StructuredText
    P6 --> StructuredText
    P7 --> StructuredText
    
    StructuredText --> Chunker[Dynamic File-Adaptive Chunker]
    Chunker --> Embedder[gemini-embedding-2 Generation 768d]
    Embedder --> VectorDB[(Save Chunks & 768d Vectors in PostgreSQL)]
    
    VectorDB --> PurgeS3[MANDATORY PURGE: s3Client.deleteObject<br/>Permanently delete staged file from S3]
    PurgeS3 --> UpdateDoc[UPDATE documents SET storage_path = NULL, raw_file_purged = TRUE]
```

### 4.2 Ingestion Orchestration with Real-Time SSE Broadcasting
```java
@Service
@RequiredArgsConstructor
public class IngestionOrchestrator {

    private final DocumentStorageService storageService;
    private final IngestionTierRouter tierRouter;
    private final DynamicChunker dynamicChunker;
    private final EmbeddingModel embeddingModel; // gemini-embedding-2 (768d)
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final IngestionJobRepository jobRepository;
    private final IngestionProgressSseService sseService;

    @Async("ingestionExecutor")
    public void processDocumentAsync(UUID jobId, UUID docId, UUID tenantId) {
        Document doc = documentRepository.findById(docId).orElseThrow();
        IngestionJob job = jobRepository.findById(jobId).orElseThrow();

        try {
            // Stage 1: Extraction & Parsing (25%)
            updateProgress(job, "EXTRACTION", 25, "PARSING");
            sseService.broadcastProgress(docId, "PARSING", 25);
            
            InputStream stream = storageService.loadStagedFile(doc.getStoragePath());
            String extractedMarkdown = tierRouter.routeAndParse(doc, stream);

            // Stage 2: Splitting & Dynamic Chunking (50%)
            updateProgress(job, "SPLITTING", 50, "CHUNKING");
            sseService.broadcastProgress(docId, "CHUNKING", 50);
            
            List<ChunkPayload> chunks = dynamicChunker.chunk(extractedMarkdown, doc.getContentType());

            // Stage 3: Vector Embedding Generation via gemini-embedding-2 (75%)
            updateProgress(job, "VECTORIZING", 75, "EMBEDDING");
            sseService.broadcastProgress(docId, "EMBEDDING", 75);
            
            List<float[]> embeddings = embeddingModel.embed(
                chunks.stream().map(ChunkPayload::getContent).toList()
            );

            // Commit vectors & chunks to PostgreSQL
            chunkRepository.saveAll(buildChunkEntities(doc, chunks, embeddings));

            // Stage 4: S3 Purge (90%)
            updateProgress(job, "PURGING_S3", 90, "PURGING_S3");
            sseService.broadcastProgress(docId, "PURGING_S3", 90);
            
            storageService.purgeStagedFile(doc.getStoragePath());
            doc.setStoragePath(null);
            doc.setRawFilePurged(true);
            doc.setStatus(DocumentStatus.INDEXED);
            documentRepository.save(doc);

            // Stage 5: Completion (100%)
            updateProgress(job, "COMPLETED", 100, "COMPLETED");
            sseService.broadcastProgress(docId, "COMPLETED", 100);
            sseService.completeStream(docId);

        } catch (Exception ex) {
            if (doc.getStoragePath() != null) {
                storageService.purgeStagedFile(doc.getStoragePath());
                doc.setStoragePath(null);
            }
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(ex.getMessage());
            documentRepository.save(doc);

            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
            jobRepository.save(job);
            
            sseService.broadcastError(docId, ex.getMessage());
        }
    }

    private void updateProgress(IngestionJob job, String stage, int percent, String status) {
        job.setStage(stage);
        job.setProgressPercent(percent);
        job.setStatus(JobStatus.valueOf(status));
        jobRepository.save(job);
    }
}
```

---

## 5. Query Routing & Fallback Engine Design

### 5.1 Detailed Execution Workflow

```mermaid
stateDiagram-v2
    [*] --> VectorRetrieval: User Query Received
    
    state VectorRetrieval {
        [*] --> GenerateQueryEmbedding
        GenerateQueryEmbedding --> ExecutePgVectorQuery: gemini-embedding-2 (768d)
        ExecutePgVectorQuery --> RankChunks: HNSW Cosine Search (WHERE tenant_id = :id)
    }
    
    VectorRetrieval --> SufficiencyCheck: Top-K Chunks Retrieved
    
    state SufficiencyCheck {
        [*] --> CheckChunkCount
        CheckChunkCount --> LocalInsufficient: Count == 0
        CheckChunkCount --> CheckCosineScore: Count > 0
        CheckCosineScore --> LocalSufficient: Max Cosine Score >= 0.70
        CheckCosineScore --> LocalInsufficient: Max Cosine Score < 0.70
    }
    
    SufficiencyCheck --> LocalSynthesis: LocalSufficient
    SufficiencyCheck --> WebFallback: LocalInsufficient
    
    state LocalSynthesis {
        [*] --> BuildLocalPrompt: System Prompt + Chunks
        BuildLocalPrompt --> CallFixedLLM: Fixed gemini-3.8-flash
        CallFixedLLM --> ReturnLocalResponse: source = LOCAL_KB + Citations
    }
    
    state WebFallback {
        [*] --> QueryTavily: Tavily Search API (maxResults = 5)
        QueryTavily --> EvaluateWebResults
        EvaluateWebResults --> WebSynthesis: Results > 0
        EvaluateWebResults --> ExplicitFail: Results == 0 or Error
    }
    
    state WebSynthesis {
        [*] --> BuildWebPrompt: System Prompt + Web Snippets
        BuildWebPrompt --> CallFixedLLMWeb: Fixed gemini-3.8-flash
        CallFixedLLMWeb --> ReturnWebResponse: source = WEB_SEARCH + Web Links
    }
    
    state ExplicitFail {
        [*] --> BuildDeterministicMessage
        BuildDeterministicMessage --> ReturnFailResponse: source = NONE, "No relevant data found..."
    }
    
    LocalSynthesis --> [*]
    WebSynthesis --> [*]
    ExplicitFail --> [*]
```

### 5.2 Deterministic Fallback Specification
If Tavily web search yields zero results, or if the Tavily service encounters an error or timeout:
```json
{
  "answer": "No relevant data found in your knowledge base, and fallback web search returned no matching results.",
  "source": "NONE",
  "citations": [],
  "webSources": [],
  "confidenceScore": 0.0
}
```

---

## 6. Model Context Protocol (MCP) Server Design

The Spring Boot backend acts as a full-featured Model Context Protocol (MCP) Server.

### 6.1 MCP Transport Endpoints
- **SSE Transport Ingress:** `GET /mcp/sse`
  - Client initiates an HTTP connection with header `Accept: text/event-stream`.
  - Authentication: `Authorization: Bearer <mcp_api_key>` or query parameter `?token=<mcp_api_key>`.
  - Server establishes an SSE session, generates a unique session ID, and emits an `endpoint` event specifying the message ingress URL:
    ```text
    event: endpoint
    data: /mcp/messages?sessionId=e4b7c128-89ab-4c31-b841-f51952f08a90
    ```
- **Message Ingress:** `POST /mcp/messages?sessionId=...`
  - External LLM sends standard JSON-RPC 2.0 messages (`initialize`, `tools/list`, `tools/call`).

### 6.2 External Client Configuration
Users can connect Claude Desktop or Cursor to their private knowledge base using the configuration below generated in their settings modal:

```json
{
  "mcpServers": {
    "synapse-my-kb": {
      "url": "https://api.synapsemcp.com/mcp/sse",
      "headers": {
        "Authorization": "Bearer syn_mcp_a1b2c3d4e5f6789012345678"
      }
    }
  }
}
```

---

## 7. REST API Endpoint Specifications

### 7.1 Authentication Endpoints
#### `POST /api/v1/auth/signup`
- **Request Body:**
  ```json
  {
    "email": "architect@enterprise.com",
    "password": "SecurePassword123!",
    "fullName": "Jane Architect"
  }
  ```
- **Response (201 Created):**
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "email": "architect@enterprise.com",
      "fullName": "Jane Architect"
    },
    "knowledgeBaseId": "f9e8d7c6-b5a4-3210-fedc-ba9876543210"
  }
  ```

#### `POST /api/v1/auth/signin`
- **Request Body:**
  ```json
  {
    "email": "architect@enterprise.com",
    "password": "SecurePassword123!"
  }
  ```
- **Response (200 OK):**
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "email": "architect@enterprise.com",
      "fullName": "Jane Architect"
    },
    "knowledgeBaseId": "f9e8d7c6-b5a4-3210-fedc-ba9876543210"
  }
  ```

---

### 7.2 Document Management & Progress Stream Endpoints

#### `POST /api/v1/documents/upload`
- **Headers:** `Authorization: Bearer <jwt>`, `Content-Type: multipart/form-data`
- **Parameters:** `file` (MultipartFile)
- **Response (202 Accepted):**
  ```json
  {
    "documentId": "4c94b7c6-8299-4c8d-b0ad-54ba1d48c909",
    "jobId": "8b7a6c5d-4e3f-2109-8765-43210fedcba9",
    "fileName": "Q3_Financial_Projections.xlsx",
    "fileSize": 3450820,
    "ingestionTier": "TIER_2_JAVA_PARSER",
    "status": "QUEUED",
    "rawFilePurged": false
  }
  ```

#### `GET /api/v1/documents/{id}/progress-stream`
- **Headers:** `Authorization: Bearer <jwt>`, `Accept: text/event-stream`
- **Response (200 OK - SSE Stream):**
  ```text
  event: progress
  data: {"documentId":"4c94b7c6-8299-4c8d-b0ad-54ba1d48c909","stage":"PARSING","progressPercent":25,"status":"PARSING"}

  event: progress
  data: {"documentId":"4c94b7c6-8299-4c8d-b0ad-54ba1d48c909","stage":"CHUNKING","progressPercent":50,"status":"CHUNKING"}

  event: progress
  data: {"documentId":"4c94b7c6-8299-4c8d-b0ad-54ba1d48c909","stage":"EMBEDDING","progressPercent":75,"status":"EMBEDDING"}

  event: progress
  data: {"documentId":"4c94b7c6-8299-4c8d-b0ad-54ba1d48c909","stage":"PURGING_S3","progressPercent":90,"status":"PURGING_S3"}

  event: progress
  data: {"documentId":"4c94b7c6-8299-4c8d-b0ad-54ba1d48c909","stage":"COMPLETED","progressPercent":100,"status":"INDEXED"}
  ```

#### `GET /api/v1/documents`
- **Headers:** `Authorization: Bearer <jwt>`
- **Response (200 OK):**
  ```json
  [
    {
      "id": "4c94b7c6-8299-4c8d-b0ad-54ba1d48c909",
      "name": "Q3_Financial_Projections.xlsx",
      "contentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "fileSize": 3450820,
      "status": "INDEXED",
      "rawFilePurged": true,
      "chunkCount": 42,
      "createdAt": "2026-10-12T19:40:00Z"
    }
  ]
  ```

#### `DELETE /api/v1/documents/{id}`
- **Headers:** `Authorization: Bearer <jwt>`
- **Response (204 No Content):** Deletes document metadata and cascade-deletes all associated chunks in `pgvector`. (No S3 deletion necessary as the raw file was already purged immediately post-indexing).

---

### 7.3 Conversational RAG Chat Endpoint
#### `POST /api/v1/chat`
- **Headers:** `Authorization: Bearer <jwt>`, `Accept: text/event-stream` or `application/json`
- **Request Body:**
  ```json
  {
    "query": "What are the revenue projections for Q3?"
  }
  ```
- **Response (200 OK - Local KB Match):**
  ```json
  {
    "answer": "According to the financial projections sheet, Q3 revenue target is $4.2M.",
    "source": "LOCAL_KB",
    "confidenceScore": 0.89,
    "citations": [
      {
        "documentId": "4c94b7c6-8299-4c8d-b0ad-54ba1d48c909",
        "documentName": "Q3_Financial_Projections.xlsx",
        "chunkId": "1a2b3c4d-5e6f-7890-1234-567890abcdef",
        "snippet": "Q3 Revenue Target: $4,200,000 across Enterprise sales...",
        "similarityScore": 0.89
      }
    ],
    "webSources": []
  }
  ```

---

### 7.4 MCP API Key Management Endpoints
- `POST /api/v1/keys/mcp` — Generates a new API key; returns plaintext key once (`syn_mcp_...`).
- `GET /api/v1/keys/mcp` — Lists existing keys with prefix, creation date, and last used timestamp.
- `DELETE /api/v1/keys/mcp/{id}` — Revokes the specified MCP API key immediately.
