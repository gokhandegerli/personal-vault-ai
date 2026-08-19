# personal-vault-ai

A **RAG (Retrieval-Augmented Generation)** service over a local folder of Markdown/DOCX
documents. Ask questions about your own notes and get answers grounded in them, with the
source files listed for every answer.

Built on **Java 25 · Spring Boot 4 · Spring AI 2.0**. Embeddings are computed **locally**
via Ollama, so indexed documents never leave the machine. The chat model is pluggable
(local Ollama or any OpenAI-compatible endpoint). Three vector stores are supported and
selected by Spring profile — no code changes.

```
                    ┌──────────────────────────────────────────────┐
   Question          │  documents ──► chunk ──► embed ──► store     │
                     └──────────────────────────────────────────────┘
        │                              ▲
        ▼                              │ similar chunks (topK = 6)
   embed the question                  │
   (same model)                        │
        │                              │
        └────►  question + retrieved chunks ──► LLM ──► answer + sources
```

---

## Why it exists

| Problem | How this service solves it |
|---|---|
| An LLM does not know your private documents | Documents are embedded into a vector store and supplied to the model as context |
| Models hallucinate | Answers are constrained to retrieved context; without context the model says it does not know |
| Retraining a model is expensive | RAG needs no training — knowledge lives in the storage layer |
| Documents change constantly | `POST /api/ingest` re-indexes in place |
| Private data should not be shipped to a vendor | Embeddings run locally; only the question and the retrieved chunks reach the chat model |
| Answers must be auditable | Every response returns the source files it was built from |

---

## Design decisions

**Hybrid retrieval instead of pure vector search.** Semantic similarity alone fails on
queries that reference a document *by name* or by identifier, because embeddings are weak on
rare tokens. On the `pgvector` profile the vault store is wrapped in a decorator that runs
two searches — HNSW cosine top-20 and PostgreSQL `tsvector` full-text top-20 — and fuses the
two ranked lists with **Reciprocal Rank Fusion (k=60)** to produce the final top-6. Rank
fusion avoids having to normalise two incomparable score scales (cosine distance vs
`ts_rank`). The `search_vector` generated column and its GIN index are created at startup by
idempotent DDL, so enabling this needs no re-ingest.

**Local embeddings, remote chat.** RAG needs embeddings twice: once per chunk at index time
and once per question at query time. Running them locally (`bge-m3`, 1024 dimensions,
multilingual, CPU-friendly) keeps the corpus on the machine. Only the question plus the six
retrieved chunks are sent to the chat model.

**Pluggable store behind one interface.** `simple` (JSON file, no server), `chroma`, and
`pgvector` all sit behind Spring AI's `VectorStore`, wired as `@Profile` beans. Switching
store is a profile change.

**Ingestion is guarded.** Two concurrent ingests used to write the same records twice,
which corrupted retrieval quality. Ingestion now takes an in-process `ReentrantLock` for a
single JVM plus a PostgreSQL **advisory lock** to cover multiple instances; a second request
returns `skipped` instead of duplicating work.

**Directory index documents.** During ingestion a synthetic "topic list" document is
generated per directory (`isIndex: true`, source = directory path, excluded from splitting).
This lets module-scoped questions ("cover everything in section 01") see the full topic list
rather than depending on individual content chunks.

**Corrections are stored separately.** Feedback with a corrected answer goes into its own
vector store and is retrieved alongside the main corpus. The indexed documents are never
modified.

---

## Quick start

Requirements: **Java 25**, **Maven 3.9+**, **Docker** (for the `chroma` / `pgvector`
profiles), **Ollama** (embeddings).

```bash
# 1. embedding model (CPU is fine; 1024-dim, multilingual)
ollama pull bge-m3

# 2. vector store services — not needed for the `simple` profile
docker compose up -d      # pgvector :5433, chroma :8000

# 3. run
mvn spring-boot:run                                              # simple (default)
mvn spring-boot:run -Dspring-boot.run.profiles=chroma
mvn spring-boot:run -Ppgvector -Dspring-boot.run.profiles=pgvector
```

Point the service at your documents with `VAULT_PATH` (default `../.vault`), then index and ask:

```bash
curl -X POST localhost:8080/api/ingest
# {"storeType":"PgVectorStore","filesRead":166,"chunksWritten":1034,"state":"idle"}

curl -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What is the saga pattern and when should I use it?"}'
# {"answer":"...","sources":[{"file":"architecture/saga-pattern.md","excerpt":"..."}]}
```

`./start.sh` does all of the above in one command (starts Ollama if needed, waits for the
stores to report healthy, launches the app in a `tmux` session, waits for HTTP 200).

A minimal web UI with streaming and conversation history lives at
`src/main/resources/static/index.html`.

---

## API

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/ingest` | Scan, chunk, embed and store the configured document root |
| `POST` | `/api/chat` | `{"message":"...","sessionId":"..."}` → `{answer, sources[]}` |
| `POST` | `/api/chat/stream` | Same, as an SSE token stream (`text/event-stream`) |
| `POST` | `/api/feedback` | Submit a corrected answer → stored in a separate correction store |
| `GET` | `/api/stores` | Active store type and index statistics / ingest progress |
| `GET` | `/api/conversations` | Conversation list |
| `GET` | `/api/conversations/{id}` | Messages of one conversation, with sources |
| `DELETE` | `/api/conversations/{id}` | Delete a conversation |
| `GET` | `/actuator/health` | Health |

Conversation history is stored as JSON files, independent of the vector store, so it behaves
identically on all three profiles. The full history is kept on disk; the last 20 messages are
sent to the model.

---

## Configuration

| Key | Default | Description |
|---|---|---|
| `app.rag.root-path` | `../.vault` | Document root to index (env `VAULT_PATH`) |
| `app.rag.top-k` | `6` | Chunks retrieved per question |
| `app.rag.chunk-size` | `400` | Chunk size in tokens |
| `app.rag.excluded-dirs` | `.git,.obsidian,.trash,temp,assets` | Directories to skip |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama endpoint (env `OLLAMA_BASE_URL`) |
| `spring.ai.ollama.embedding.model` | `bge-m3` | Embedding model (index + query) |
| `app.vector-store.chroma.url` | `http://localhost:8000` | Chroma endpoint |
| `spring.datasource.*` | `localhost:5433` | PGVector connection (`pgvector` profile) |
| `app.conversations.dir` | `data/conversations` | Conversation history directory |

Switching chat/embedding provider is configuration only — application code is untouched:

```yaml
spring:
  ai:
    model:
      chat: openai        # or ollama
      embedding: ollama   # keep local
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-4o-mini
```

> If you change the embedding model on `pgvector`, update
> `app.vector-store.pgvector.dimensions` to match (`bge-m3` → 1024,
> `text-embedding-3-small` → 1536) and recreate the table.

---

## Vector stores

| | `simple` (default) | `chroma` | `pgvector` |
|---|---|---|---|
| Service required | none (JSON file) | Docker `:8000` | Docker `:5433` |
| Search | brute force | HNSW | **hybrid**: HNSW + `tsvector` FTS, RRF fusion |
| Metadata filtering | limited | yes | yes |
| Production ready | no | partly | yes |

---

## Project layout

```
src/main/java/com/gokhandegerli/personalvaultai/
├── config/       AppProperties, ChatConfig, VectorStoreConfig (@Profile), WebConfig
├── advisor/      CombinedQuestionAnswerAdvisor — merges corpus + correction context
├── service/      IngestionService (ETL), ChatService, CorrectionService,
│                 HybridRetriever, HybridSearchVectorStore, JsonChatMemory
├── dto/          request/response records
└── web/          AiController (REST + SSE)

src/pgvector/     profile-scoped sources: HybridSearchService (FTS + RRF),
                  PgVectorStoreConfig, PgVectorSearchIndexInitializer (startup DDL)
```

---

## Notes

- `data/` (vector store JSON, corrections, conversation history) is git-ignored — indexed
  content never enters the repository.
- The Postgres credentials in `docker-compose.yml` are local development defaults; set real
  ones via `PGVECTOR_PASSWORD` for any shared deployment.
- Troubleshooting order: `docker compose logs`, `ollama list`, `/actuator/health`.

## Roadmap

- [x] End-to-end RAG over three vector stores, profile-selected
- [x] SSE streaming, conversation history, feedback/correction loop
- [x] Hybrid search (vector + full text, RRF)
- [x] Directory index documents for module-scoped questions
- [ ] Metadata filters (restrict search to a subtree)
- [ ] Filesystem watch / automatic re-ingest
- [ ] Expose as an MCP server (Spring AI 2.0 MCP)
