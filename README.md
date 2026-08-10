# personal-vault-ai

Kişisel Obsidian vault'undaki notları (`.vault/`) besleyen bir
**RAG (Retrieval-Augmented Generation)** chatbot'u. Spring AI 2.0 + Spring Boot 4 üzerine kurulu;
**embedding local Ollama'da** (`bge-m3`), **chat OpenCode Zen'de** (ücretsiz `big-pickle`) çalışır;
üç farklı vector store arasında tek profil değiştirerek geçiş yapabilirsin.

> Kısaca: `.vault` içindeki markdown notlarına soru sor. Cevap, yalnızca o notlardan çekilen
> bilgiye dayanır — model kendi eğitim verisinden değil, **senin dokümanlarından** konuşur.

---

## Ne? (What)

> RAG, LLM, embedding, vector store ve conversation memory kavramlarının **teorik açıklaması**
> bu repoda değil, bilgi vault'unda tutulur: `[[08-ai]]` → `[[08-04-rag-nedir]]`.

```
                    ┌──────────────────────────────────────────────┐
   Soru             │                    Vault                     │
   "Saga pattern    │  .vault/*.md ──► chunk'la ──► embed ──► store │
    nedir?"         └──────────────────────────────────────────────┘
        │                              ▲
        ▼                              │ benzer chunk'lar
   Embed (soru da aynı                │ (retrieval, topK=6)
   modelle vektöre çevrilir)          │
        │                              │
        └────►  Soru + bulunan chunk'lar ──► LLM ──► Cevap
```

### Bu proje ne yapıyor?

- `.vault` klasörünü tarar, markdown + docx dosyalarını indeksler (`POST /api/ingest`).
- İndekslenen bilgi üzerinden soruları yanıtlar (`POST /api/chat`) ve **hangi dosyalardan**
  yanıtladığını gösterir (kaynaklar).
- Cevapları **streaming** (token token akış) olarak da verir (`POST /api/chat/stream`).
- Üç vector store'u destekler ve **profil seçimi** ile değiştirilir: `simple`, `chroma`, `pgvector`.

---

## Neden? (Why)

| Sorun | Bu projenin çözümü |
|---|---|
| LLM senin özel notlarını bilmez | Notlar embedding'lenip vector store'a konur, LLM'e bağlam olarak verilir |
| Model "uydurur" (hallucination) | Cevap yalnızca getirilen bağlama dayanır; bağlam yoksa "bilmiyorum" der |
| Modeli yeniden eğitmek çok pahalı | RAG eğitim gerektirmez; bilgiyi depolama tarafında tutar |
| Notlar sürekli değişir | `POST /api/ingest` ile saniyeler içinde yeniden indekslenir |
| Verinin dışarı çıkması istenmez | Embedding local Ollama'da yapılır; vault notlarının tamamı asla dışarı çıkmaz. Zen'e yalnızca **soru + retrieve edilen birkaç chunk** gider (gizlilik sıkıysa bkz. "Model sağlayıcıyı değiştirmek" → tamamen local LLM) |
| "LLM'e soru sor" yerine "belgemden soru sor" | Gizlilik + doğruluk: kaynaklar gösterilir, doğrulanabilir |

### Neden Spring AI?

- **Spring Boot 4 / Spring Framework 7 ile doğal uyum** — mevcut Java stack'ine sıfırdan yeni bir
  teknoloji katmazsın.
- Model sağlayıcıdan bağımsız API: Ollama → OpenAI → bulut modellere geçişte kod değişmez.
- Vector store'lar için tek `VectorStore` arayüzü; store değiştirmek profili değiştirmek kadardır.
- `ChatClient`, `QuestionAnswerAdvisor`, ETL (`DocumentReader`/`Transformer`/`Writer`) gibi RAG
  bileşenleri hazır gelir.

### Neden Ollama (local embedding)?

- RAG'ın **iki yerinde** embedding gerekir: hazırlıkta (chunk'ları vektöre çevirme) ve sorguda
  (soruyu vektöre çevirme). Zen'in ücretsiz modelleri yalnızca **chat** üretir, embedding üretemez.
- Embedding'i de buluta taşırsan **vault'un tamamı** (tüm özel notlar) dışarıya embed edilmek üzere
  gönderilir. Local'de tutmak notları makineden çıkarmaz.
- `bge-m3` çok dilli embedding modelidir (EN + TR notlar için daha iyi eşleşme) ve CPU'da rahat çalışır — ek GPU gerekmez.
- Zen'e giden tek şey: **soru + retrieve edilen birkaç chunk** (topK=6). Notların tamamı asla dışarı çıkmaz.

### Ollama hem LLM hem embedding çalıştırabilir

Ollama bir **model çalıştırıcı**dır; tek programla iki tür modeli de indirip servis eder. "Ollama
deyince LLM akla geliyor" algısı doğrudur — ama tek bir `ollama pull <model>` komutuyla embedding
modeli de kurulur:

| Model türü | Ne üretir | Örnekler | Bu projede |
|---|---|---|---|
| Chat modeli (LLM) | Metinden metin | `qwen2.5`, `llama3.2`, `deepseek-r1` | Kullanılmıyor — chat Zen'den geliyor |
| Embedding modeli | Metinden vektör | `bge-m3` (1024), `nomic-embed-text`, `mxbai-embed-large` | Embedding burada (local) |

İkisi de `ollama list`'te görünür; Ollama aynı anda birden çok model barındırır. Bu projede sadece
embedding tarafı seçildi çünkü chat Zen'de ücretsiz geliyor. Chat'i de Ollama'ya taşımak istersen
sağlayıcı ayarını değiştirmek yeterli (kod değişmez):

```yaml
spring:
  ai:
    model:
      chat: ollama          # chat'i de Ollama'ya al (LLM)
      embedding: ollama     # embedding zaten Ollama'da
    ollama:
      chat:
        model: llama3.2
      embedding:
        model: bge-m3
```

> Chat'i Ollama'ya almak "daha yerel" olur ama CPU'da yavaştır (LLM GPU ister), Zen ise bulutta
> hızlı ve ücretsizdir. Bu proje "embedding local (gizlilik) + chat bulut (hız/maliyet)" dengesini
> seçti. Daha sıkı gizlilik istiyorsan LLM'i de Ollama'ya taşıyabilirsin.

---

## Nasıl? (How)

### En hızlı yol: tmux + arka plan çalıştırma

Uygulama genelde bir tmux session'ında arka planda çalışır — terminali kapatınca ölmez, log'a bakarız.

```bash
# Başlat (arka planda, log /tmp/pva-boot.log'a)
tmux new-session -d -s pva -c /home/gokhan-degerli/Documents/personal/personal-vault-ai \
  "mvn -q spring-boot:run -Ppgvector -Dspring-boot.run.profiles=pgvector,zen > /tmp/pva-boot.log 2>&1"

# Log canlı izle
tmux attach -t pva

# Hazır olma kontrolü (200 dönünce kullanılabilir)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/conversations
```

**Restart** (kod değişince):

```bash
tmux kill-session -t pva      # durdur
mvn -q compile                # değişikliği derle
# yukarıdaki tmux başlatma komutunu tekrar çalıştır
```

Aktif kurulum: `pgvector,zen` profilleri (chat → OpenCode Zen ücretsiz, embedding → local Ollama).
UI: `src/main/resources/static/index.html` tarayıcıda açılır (`file://`), CORS `/api/**` için yapılandırıldı.

### Gereksinimler

- **Java 25** (sdkman: `sdk install java 25.0.3-tem`)
- **Maven 3.9+**
- **Docker + docker-compose** (chroma ve pgvector profilleri için)
- **Ollama** (embedding modeli; chat Zen'den geldiği için chat tarafında Ollama gerekmez)

### 1) Ollama'yı kur ve embedding modelini indir

```bash
# Linux/macOS: tek komut (sistem yöneticisi şifresi ister)
curl -fsSL https://ollama.com/install.sh | sh

# Embedding modeli (CPU'da çalışır; 1024 boyutlu vektör üretir — TR+EN notlarda en iyi eşleşme)
ollama pull bge-m3

# Chat modeli Ollama'da çalışmaz — chat her zaman Zen (bulut) üzerinden gelir.
# Servisin ayakta olduğunu kontrol et
ollama list
```

> Ollama yoksa `sudo` istemeyen rootless kurulum: GitHub releases'ten `ollama-linux-amd64.tar.zst`
> indirilip `~/` altına açılır ve `ollama serve &` ile çalıştırılır.

### 2) Vector store altyapısını başlat (isteğe bağlı)

`simple` profili **hiçbir servis gerektirmez** (dosyaya kaydeder). Chroma/PGVector için:

```bash
docker-compose up -d
# pva-chroma   -> http://localhost:8000
# pva-pgvector -> localhost:5433 (agentdb'den bağımsız; 5432 kullanmaz)
```

### 3) Uygulamayı çalıştır

> Öncelikle yukarıdaki "En hızlı yol: tmux + arka plan çalıştırma" bölümüne bak — aktif kurulum
> (`pgvector,zen`) zaten orada. Aşağıdaki komutlar diğer profil kombinasyonları içindir.

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.3-tem
mvn spring-boot:run            # varsayılan profil: simple
```

Diğer store'lar için:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=chroma
mvn spring-boot:run -Ppgvector -Dspring-boot.run.profiles=pgvector   # pgvector ayrıca Maven profilidir
```

### 4) Vault'u indeksle

```bash
curl -X POST http://localhost:8080/api/ingest
# {"storeType":"PgVectorStore","filesRead":10,"totalFiles":166,"chunksWritten":1034,"state":"idle","skipped":[]}
```

İndeksleme sırasında her chunk Ollama ile embedding'e çevrilir — CPU'da tek bir full-ingest
~19 dk sürer (vault ~360K token, ~1034 chunk). `simple` profilde sonuç
`data/simple-vector-store.json` dosyasına kaydedilir; yeniden başlatınca otomatik yüklenir
(tekrar indekslemene gerek yok).

> **Concurrent ingest engellenir:** süreç içi `ReentrantLock` + PostgreSQL `pg_advisory_lock`.
> Ingest çalışırken gelen ikinci istek `skipped: ["ingest zaten çalışıyor"]` döner — bu, eşzamanlı
> iki ingest'in aynı kayıtları iki kez yazıp (eski hatada DB'de ~2x duplicate) eşleşmeyi bozmasını önler.
> İlerleme `GET /api/stores` ile izlenir (`state`: `idle`/`reading`/`embedding`, `filesRead`, `chunksWritten`).

### 5) Soru sor

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "Saga pattern nedir ve ne zaman kullanilir?"}'
```

Cevapla birlikte kaynak dosyalar da döner:

```json
{
  "answer": "Saga pattern, distributed transaction yönetimi için ...",
  "sources": [
    { "file": "backend/06-system-design/messaging-patterns/06-09-saga-pattern.md", "excerpt": "..." }
  ]
}
```

Streaming:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message": "Spring Boot'ta exception handling nasil yapilir?"}'
```

### 6) Store durumunu gör

```bash
curl http://localhost:8080/api/stores
# {"storeType":"PgVectorStore","filesRead":166,"chunksWritten":1034,"state":"idle"}
```

### 7) Feedback ile cevap düzelt (self-improvement)

Yanlış/eksik cevap alırsan düzeltmesini gönder; sistem bunu vault'tan **ayrı** bir
correction store'a kaydeder ve sonraki sorularda onu da context'e katar.

```bash
curl -X POST http://localhost:8080/api/feedback \
  -H 'Content-Type: application/json' \
  -d '{
    "question": "SOLID prensipleri nelerdir?",
    "answer": "eksik cevap",
    "helpful": false,
    "correctedAnswer": "SOLID: S - Single Responsibility, O - Open/Closed, ..."
  }'
# {"storedCorrections":1}
```

- `correctedAnswer` boşsa veya `helpful: true` ise kayıt edilmez (sadece count döner).
- Corrections `data/corrections.log` (metin log) + `data/corrections-vector-store.json`
  (vektör) olarak tutulur; `.vault` asla değişmez.
- Kaynaklar listesinde `correction:<soru>` şeklinde görünür.

---

## Mimari

```
┌─────────────────────────────── ETL (ingest) ───────────────────────────────┐
│  .vault/*.md   ──►  ham metin (tek Document/dosya)                          │
│  .vault/*.docx ──►  TikaDocumentReader                                      │
│                        │                                                   │
│                        ▼                                                   │
│              TokenTextSplitter (chunk = 400 token)                          │
│                        │                                                   │
│                        ▼                                                   │
│              OllamaEmbeddingModel ──► VectorStore (simple|chroma|pgvector) │
└────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────── QUERY ──────────────────────────────────────┐
│  /api/chat ──► ChatClient                                                  │
│                  └── CombinedQuestionAnswerAdvisor                          │
│                        ├── 1) soruyu embed et                              │
│                        ├── 2) topK=6 chunk'ı vault store'dan getir          │
│                        │      pgvector: HYBRID — vektör top-20 (cosine)    │
│                        │        + tsvector FTS top-20 (lexeme OR, Turkish) │
│                        │        → RRF füzyon → top-6                       │
│                        ├── 3) topK=6 chunk'ı correction store'dan getir     │
│                        └── 4) system prompt + chunk'lar + soru → LLM        │
│  Cevap + kaynak dosyalar (RETRIEVED_DOCUMENTS)                             │
└────────────────────────────────────────────────────────────────────────────┘
```

**Bileşenler ve karşılıkları:**

| Katman | Spring AI bileşeni | Dosya |
|---|---|---|
| Chat client | `ChatClient` + `CombinedQuestionAnswerAdvisor` | `config/ChatConfig.java`, `advisor/CombinedQuestionAnswerAdvisor.java` |
| ETL | raw metin okuma + `TikaDocumentReader`, `TokenTextSplitter` | `service/IngestionService.java` |
| Feedback | kullanıcı düzeltmeleri → ayrı vector store | `service/CorrectionService.java` |
| Embedding | `OllamaEmbeddingModel` (otomatik yapılandırılır) | `application.yml` |
| Vector store | `VectorStore` (profil bazlı bean) + corrections store | `config/VectorStoreConfig.java` |
| Hybrid search | decorator (`HybridSearchVectorStore` + `HybridRetriever`) → FTS+vektör+RRF | `service/HybridSearchVectorStore.java`, `service/HybridSearchService.java` (src/pgvector) |
| Hybrid schema | `search_vector tsvector` generated kolonu + GIN index (startup'ta otomatik, idempotent) | `config/PgVectorSearchIndexInitializer.java` (src/pgvector) |
| API | REST controller | `web/AiController.java` |

---

## Vector Store Karşılaştırması

| | `simple` (varsayılan) | `chroma` | `pgvector` |
|---|---|---|---|
| Amaç | POC / local test | Hafif ayrı vektör DB | Postgres içinde vektör |
| Servis | Yok (JSON dosyası) | Docker `pva-chroma:8000` | Docker `pva-pgvector:5433` |
| Metadata filtreleme | Sınırlı | Evet | Evet |
| Kalıcılık | `data/simple-vector-store.json` | `chroma-data` volume | `pgvector-data` volume |
| Arama | Brute-force (küçük veri için yeterli) | HNSW | **Hybrid**: HNSW (`vector_cosine_ops`) + tsvector FTS, RRF füzyon |
| Üretime uygunluk | ❌ | Kısmen | ✅ |
| Aktivasyon | `--spring.profiles.active=simple` | `...=chroma` | `...=pgvector` |

**Öneri:** Önce `simple` ile akışı doğrula → sonra `pgvector` (üretim kalitesi) → dilersen `chroma` ile
kıyasla. Hepsi aynı `VectorStore` arayüzünü kullandığı için kod değişmez.

**Hybrid search (yalnız `pgvector`):** vault store'u `HybridSearchVectorStore` decorator'ıyla sarılır.
Sorgu hem vektör (cosine top-20) hem **Postgres full-text** (tsvector top-20) ile aranır; iki liste
**RRF (Reciprocal Rank Fusion, k=60)** ile birleştirilip top-6 döner. FTS sorgusu, kullanıcı cümlesinin
lexeme'lerinden `'turkish'` config ile OR sorgusu kurar (stopword'ler doğal olarak elenir; sayısal
lexeme'ler atlanır). Böylece **isimle referans veren** sorgular da bulunur — örn. `"01 Summary icin
interview yapalim mi?"` artık `01-Summary.md`'yi getirir (saf vektörde bulunamıyordu). `search_vector`
kolonu startup'ta otomatik oluşturulur (idempotent DDL), mevcut satırlar otomatik doldurulur —
**re-ingest gerekmez.** Corrections store (SimpleVectorStore) saf vektörde kalır.

> **PGVector dims uyarısı:** `application-pgvector.yml` içindeki `app.vector-store.pgvector.dimensions`
> embedding modelinin boyutuna uymalıdır: `nomic-embed-text` → 768, `bge-m3` → 1024.
> Model değiştirirsen tabloyu yeniden kurmak gerekir: ingest durdur, `DROP TABLE vector_store CASCADE`
> (uygulama yeniden başlarken tabloyu ve HNSW index'i otomatik oluşturur), sonra tekrar ingest.

---

## API Referansı

| Metot | Path | Açıklama |
|---|---|---|
| `POST` | `/api/ingest` | Vault'u tarar, chunk'lar, embed eder, store'a yazar |
| `POST` | `/api/chat` | `{"message":"..."}` → `{answer, sources[]}` |
| `POST` | `/api/chat/stream` | Aynı chat, SSE (`text/event-stream`) token akışı |
| `GET` | `/api/stores` | Aktif store tipi + indeks istatistikleri |
| `GET` | `/api/conversations` | Konuşma geçmişi listesi (`{id,title,messageCount,updatedAt}`) |
| `GET` | `/api/conversations/{id}` | Tek konuşmanın mesajları (`messages[]` + her mesajın `sources[]`) |
| `DELETE` | `/api/conversations/{id}` | Konuşmayı siler |
| `GET` | `/actuator/health` | Uygulama sağlığı |

Chat isteğinde `sessionId` (`[A-Za-z0-9_-]`, 64 karakter) aynı konuşmayı tanımlar; boş/geçersizse rastgele üretilir. Konuşma geçmişi `data/conversations/{id}.json` dosyalarında saklanır — **vector store'dan bağımsızdır** (simple/chroma/pgvector üçünde de çalışır), LLM'e son 20 mesaj verilir, dosyada tam geçmiş tutulur.

---

## Konfigürasyon

`src/main/resources/application.yml` (ve `application-chroma.yml`, `application-pgvector.yml`):

| Anahtar | Varsayılan | Açıklama |
|---|---|---|
| `app.rag.root-path` | `../.vault` | İndekslenecek vault yolu (env: `VAULT_PATH`) |
| `app.rag.top-k` | `6` | Soru başına getirilen chunk sayısı |
| `app.rag.chunk-size` | `400` | Chunk boyutu (token) |
| `app.rag.excluded-dirs` | `.git,.obsidian,.trash,temp,assets` | Atlanacak klasörler |
| `app.rag.max-files` | `0` | `0`=hepsi; aksi halde ilk N dosya |
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama adresi (env: `OLLAMA_BASE_URL`) |
| `spring.ai.ollama.embedding.model` | `bge-m3` | Embedding modeli (hazırlık + sorgu; 1024 boyut) |
| `spring.ai.openai.base-url` | `https://opencode.ai/zen/v1` | Zen endpoint (yalnız `zen` profili; env: `ZEN_BASE_URL`) |
| `spring.ai.openai.chat.options.model` | `big-pickle` | Zen chat modeli (yalnız `zen` profili; env: `ZEN_CHAT_MODEL`) |
| `app.vector-store.simple.file` | `data/simple-vector-store.json` | Simple store kalıcılık dosyası |
| `app.vector-store.chroma.url` | `http://localhost:8000` | Chroma adresi |
| `app.vector-store.chroma.collection` | `personal-vault` | Chroma koleksiyonu |
| `spring.datasource.*` | localhost:5433/postgres | PGVector bağlantısı (yalnız `pgvector` profili) |
| `app.conversations.dir` | `data/conversations` | JSON konuşma geçmişi dizini (tüm profillerde ortak) |

### Model sağlayıcıyı değiştirmek (Ollama → OpenAI)

```yaml
# pom.xml'e ekle: org.springframework.ai:spring-ai-starter-model-openai
spring:
  ai:
    model:
      chat: openai
      embedding: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-4o-mini
      embedding:
        model: text-embedding-3-small
```

`spring.ai.model.chat` / `spring.ai.model.embedding` ayarı sağlayıcıyı seçer; uygulama kodu değişmez.
> `pgvector` kullanıyorsan embedding boyutunu `text-embedding-3-small` (1536) ile güncelle.

### Zen profili (OpenCode ücretsiz chat modelleri)

`application-zen.yml` chat modelini OpenCode Zen'e bağlar (API key gerekmez), embedding **local Ollama'da
kalır** (vektörler yeniden index'lenmez):

```yaml
spring:
  ai:
    model:
      chat: openai      # Zen (OpenAI-compatible)
      embedding: ollama # local kalır
    openai:
      api-key: ""       # Zen ücretsiz modeller key istemez
      base-url: https://opencode.ai/zen/v1
      chat:
        options:
          model: big-pickle   # ücretsiz; env: ZEN_CHAT_MODEL
    ollama:
      embedding:
        model: bge-m3
```

```bash
# vector store profili ile birlikte aktifleştir
mvn spring-boot:run -Dspring-boot.run.profiles=simple,zen
```

Ücretsiz Zen modelleri: `big-pickle`, `deepseek-v4-flash-free`, `mimo-v2.5-free`, `nemotron-3-ultra-free` vb.
> Not: Zen ücretsiz modeller veriyi iyileştirme amaçlı kullanabilir — private veri için varsayılan
> (`simple`) profil (Ollama) önerilir.

---

## Klasör Yapısı

```
personal-vault-ai/
├── docker-compose.yml              # chroma + pgvector (agentdb'den bağımsız, 5433)
├── pom.xml                         # Spring Boot 4.0.5 + Spring AI 2.0.0 BOM
├── README.md
└── src/main/
    ├── java/com/gokhandegerli/personalvaultai/
    │   ├── PersonalVaultAiApplication.java
    │   ├── config/
    │   │   ├── AppProperties.java     # @ConfigurationProperties("app")
    │   │   ├── ChatConfig.java        # ChatClient + QuestionAnswerAdvisor
    │   │   └── VectorStoreConfig.java # @Profile: simple|chroma|pgvector
    │   ├── dto/                       # ChatRequest/Response, Source, IngestResponse
    │   ├── service/
    │   │   ├── IngestionService.java  # ETL pipeline
    │   │   └── ChatService.java       # RAG chat + kaynak çıkarımı
    │   └── web/
    │       └── AiController.java      # REST API
    └── resources/
        ├── application.yml            # simple profil (varsayılan)
        ├── application-zen.yml        # chat → OpenCode Zen (embedding local)
        ├── application-chroma.yml
        └── application-pgvector.yml
```

---

## Terimler Sözlüğü

Terminoloji (LLM, embedding, RAG, chunk, topK, hallucination, system prompt, retrieval, Spring AI,
ChatClient, Advisor, MCP) teorik anlamlarıyla birlikte bilgi vault'unda tutulur:
`[[08-ai]]` → `[[08-09-terimler]]`. Bu projedeki kullanımı koddan ve `application.yml`'den okunur.

---

## Yol Haritası

- [x] API + curl ile uçtan uca RAG (3 vector store, profil seçimi)
- [ ] Basit web UI (Thymeleaf/React + SSE streaming ile sohbet arayüzü)
- [ ] `temp/` docx'lerin otomatik temizliği / yeni dosya takibi (watch)
- [ ] Metadata filtreleri (örn. yalnızca `backend/` klasörünü ara)
- [ ] MCP servisi olarak expose etme (Spring AI 2.0 MCP desteği)
- [ ] Hybrid arama (vektör + anahtar kelime) kalitesi karşılaştırması

---

## Lisans ve Notlar

- Private kişisel proje; depodaki tüm bilgi `.vault` içeriğidir ve gizlidir.
- `data/` (simple store JSON) ve `target/` git'e gönderilmez.
- Sorunlarda: `docker-compose logs`, `ollama list`, `/actuator/health` ilk bakılacak yerlerdir.
