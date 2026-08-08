# personal-vault-ai

Kişisel Obsidian vault'undaki notları (`.vault/`) besleyen bir
**RAG (Retrieval-Augmented Generation)** chatbot'u. Spring AI 2.0 + Spring Boot 4 üzerine kurulu;
**embedding local Ollama'da** (`nomic-embed-text`), **chat OpenCode Zen'de** (ücretsiz `big-pickle`) çalışır;
üç farklı vector store arasında tek profil değiştirerek geçiş yapabilirsin.

> Kısaca: `.vault` içindeki markdown notlarına soru sor. Cevap, yalnızca o notlardan çekilen
> bilgiye dayanır — model kendi eğitim verisinden değil, **senin dokümanlarından** konuşur.

---

## Ne? (What)

### RAG nedir?

RAG = **R**etrieval-**A**ugmented **G**eneration (Getir-güçlendir-üret).

Bir LLM (Large Language Model — büyük dil modeli) tek başına yalnızca **eğitildiği veriyi** bilir.
Senin özel notların, proje dokümanların, iç mimarin o modelin eğitim verisinde yoktur. RAG bu açığı
kapatır: model çağrısından **önce** kendi veri kaynağında (burada `.vault`) arama yapar, ilgili
parçaları bulur, bunları modelin prompt'una (bağlam olarak) ekler ve modeli yalnızca o bağlama
göre cevap vermeye zorlar.

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

**Akışın adımları:**

1. **Extract (çıkar):** `.vault` içindeki `.md` / `.docx` dosyaları okunur.
2. **Transform (dönüştür):** Her dosya **chunk'lara** (küçük parçalara) bölünür ve her chunk bir
   **embedding**'e (sayı vektörü) çevrilir. Aynı anlamdaki metinler aynı yöne bakan vektörler üretir.
3. **Load (yükle):** Bu vektörler bir **vector store**'a yazılır.
4. **Retrieve (getir):** Soru geldiğinde soru da embed edilir, store'da ona en "yakın" `topK` chunk
   bulunur.
5. **Augment + Generate (güçlendir + üret):** Bulunan chunk'lar soruyla birlikte LLM'e verilir;
   LLM yalnızca bu bağlama dayanarak cevap üretir ve hangi dosyadan aldığını (kaynak) geri döner.

### RAG bir yazılım değil, bir akıştır

"RAG" diye kurulacak tek bir program yoktur. RAG, **üç ayrı sorumluluğu** tek bir akışta birleştiren
bir tekniktir. Karıştırılan ifadeleri şöyle ayrıştırmak faydalı:

| İfade | Ne yapar | Soru 1'deki rolü |
|---|---|---|
| **Embedding modeli** | Metni vektöre (sayı dizisine) çevirir | Hazırlıkta chunk'ları, sorguda soruyu vektöre çevirir |
| **Vector store** | Vektörleri saklar, "en yakın" olanı bulur | Retrieval (getir) adımı |
| **LLM (chat modeli)** | Metinden metin üretir (cevabı yazar) | Generate (üret) adımı |
| **Ollama** | Uygulama; **embedding modelini** local'de çalıştırır | Embedding'i üretir (hem hazırlık hem sorguda) |
| **Zen** | Bulut servis; **chat modelini** (LLM) barındırır | Cevabı üretir |
| **Spring AI** | Java çatısı; yukarıdaki parçaları birbirine bağlayan kodu verir | Akışı uçtan uca ören iskelet |

**Özet:** RAG = "getir (retrieve) + güçlendir (augment) + üret (generate)". Bu akışta
embedd olmayı **Ollama**, cevabı yazmayı **Zen**, getirme işini **vector store** yapar; bu parçaları
birbirine bağlayan kod ise bu projedir.

### RAG olmayan LLM'ler: ChatGPT, Gemini, Abacus

Bu proje **RAG üzerine** kurulu: dış bellek (vector store) her soruda tazelenir. Peki ChatGPT,
Gemini, Abacus gibi milyarlarca veriyle eğitilmiş online LLM'ler aynı yapıda mı? Hayır — onların
adı **LLM chatbot / foundation-model sohbet asistanı**dır. Fark veri kaynağındadır:

| | RAG (bu uygulama) | ChatGPT, Gemini, Abacus |
|---|---|---|
| Veri kaynağı | **Non-parametric hafıza**: dış bellek (vector store), her soruda taze getirilir | **Parametric hafıza**: bilgi eğitim sırasında milyarlarca ağırlığa gömülür |
| Arama | Evet (retrieval) | Genelde hayır — doğrudan model üretir |
| Cevap | Yalnızca dokümanlardan | Yalnızca eğitim verisinden (güncelliği kesilir) |
| Güncellik | İndekslediğin anda güncel | Eğitim kut tarihiyle sınırlı |

ChatGPT/Gemini "RAG olmadan çalışan LLM" örneğidir: bilgiyi aramaz, eğitimden kalan ağırlıklarından
tahmin üretir. Bazıları internette gezinme (`web browsing`) özelliği sunar — o, uygulamaya eklenmiş
basit bir arama/RAG katmanıdır; çekirdek üretim yine parametriktir. Teknik olarak bu modellerin iç
mimarisi **transformer + autoregressive**'tir (sıradaki token'ı tahmin eder); "kaç milyar parametre"
denen şey, bilginin saklandığı alanın büyüklüğüdür.

### LLM bu işi nasıl yapıyor? (LLM'e ne gider, o ne yapar?)

LLM'e tek başına "soru" gitmez; gitseydi model yalnızca **kendi eğitim verisiyle** cevap verirdi.
Gerçekte her istekte LLM'e **3 katmanlı bir prompt** gider:

```
[SYSTEM]  Sen yalnızca verilen context'e dayanarak cevap veren bir asistansın.
          Bağlamda cevap yoksa açıkça söyle. Kısa ve doğru ol.

[USER]
  Soru:    "Saga pattern nedir?"
  ─────────────────────────────
  Context (topK=6 chunk — vector store'un bulduğu ilgili parçalar):
    "…dağıtık sistemlerde uzun işlemleri yönetmek için saga pattern…"
    "…her adım kendi local transaction'ıdır; biri başarısız olursa…"
    "…compensation transaction'lar önceki adımları geri alır…"
  ─────────────────────────────
  Bu context'e dayanarak yanıtla.
```

Bu prompt uygulamada iki yerde kurulur: **system prompt** `ChatConfig.java` içinde sabit durur;
**context + soru** ise `CombinedQuestionAnswerAdvisor` tarafından kullanıcı mesajının içine yazılır.

**Aşamalar kimde?**

| Aşama | Kim yapar | Ne olur |
|---|---|---|
| İlgili chunk'ları bulmak | **Vector store** (retrieval) | Soru vektöre çevrilir, en yakın `topK` chunk bulunur |
| Bulunanları anlamlı cevaba çevirmek | **LLM** (generation) | Verilen tüm metni okur, token token yeni metin üretir |
| "Bağlam dışına çıkma" kuralı | **System prompt** | Modeli yalnızca verilen context'e zorlar |

**Kritik ayrım:** LLM bilgiyi *aramaz* — arama işini vector store yaptı. LLM, bulunmuş ham
paragrafları alır, soruya göre yeniden düzenler ve akıcı bir cevap **yazar**. "Anlamlı mesajı"
LLM çıkarır: kopuk chunk'ların soru etrafında tek akıcı metne dönüştüğü yer orasıdır. Ama bu
serbestliği sınırlayan yine system prompt'tur — model "bağlamda yoksa bilmiyorum demek" zorundadır,
bu sayede uydurma (hallucination) azalır.

**System prompt nedir?** LLM'e daha ilk kelimeyi üretmeden önce verilen "görev tanımı + davranış
kuralları" metnidir. Mesaj geçmişinden bağımsızdır, her istekte en önce gönderilir: "sen kimsin,
hangi kurallara uyacaksın". Kullanıcı sorularıyla karışmaz; o, modelin zihnini şekillendiren
prologdur. Yukarıdaki örnekte `[SYSTEM]` satırı tam olarak budur.

### Örnek case: "Stack vs Heap interview" — hangisi LLM, hangisi RAG?

Bu projede "Beni Stack vs Heap konusunda interview yap" diyince gelişen akış, sorumlulukların
ayrımını net gösterir:

```
[Kullanıcı] "Stack vs Heap interview yap"
   │
   ├─► Retrieval (RAG): soru embed edilir, topK=6 chunk getirilir
   │      └─ kaynaklar: 01-01-stack-vs-heap.md, 01-03-stackoverflow..., 01-Summary.md
   │
   └─► LLM (Big Pickle):
          "Soru 1: Stack'in temel özellikleri nelerdir?"   ← LLM yazdı
```

Her turda aynı döngü tekrarlanır — kullanıcı cevap verir, yeni soru gelir:

| Gördüğün davranış | Kim üretiyor |
|---|---|
| "Soru 1: ... özellikleri nelerdir?" (yeni soru sorma) | **LLM** |
| "Cevabın doğru. Ek olarak şunları da söyleyebilirsin: ..." (onaylama + eksik ekleme) | **LLM** |
| "Soru 2: Peki Heap'in özellikleri nelerdir?" (ilerletme) | **LLM** |
| Altta listelenen kaynak dosyalar | **RAG** (retrieval) |

**Kural:** **Kutu = LLM, kutunun içindekiler (kaynaklar) = RAG.** Interview akışının tamamı —
soru sormak, cevabı değerlendirmek, eksikleri eklemek, bir sonraki soruya geçmek — LLM'in yazdığı
metindir; programlanmış bir state machine değildir. Sistem (RAG) yalnızca her tur için ilgili
chunk'ları ve önceki konuşmayı (chat memory) LLM'e hazırlar. "Doğru kabul etme" de LLM'in
yargısıdır ve yanılabilir — bu yüzden kendi sorduğu soruyu unutup çelişebildiğini daha önce gördük.

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
- `nomic-embed-text` CPU'da rahat çalışır — ek GPU gerekmez.
- Zen'e giden tek şey: **soru + retrieve edilen birkaç chunk** (topK=6). Notların tamamı asla dışarı çıkmaz.

### Ollama hem LLM hem embedding çalıştırabilir

Ollama bir **model çalıştırıcı**dır; tek programla iki tür modeli de indirip servis eder. "Ollama
deyince LLM akla geliyor" algısı doğrudur — ama tek bir `ollama pull <model>` komutuyla embedding
modeli de kurulur:

| Model türü | Ne üretir | Örnekler | Bu projede |
|---|---|---|---|
| Chat modeli (LLM) | Metinden metin | `qwen2.5`, `llama3.2`, `deepseek-r1` | Kullanılmıyor — chat Zen'den geliyor |
| Embedding modeli | Metinden vektör | `nomic-embed-text`, `all-minilm`, `mxbai-embed-large` | Embedding burada (local) |

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
        model: nomic-embed-text
```

> Chat'i Ollama'ya almak "daha yerel" olur ama CPU'da yavaştır (LLM GPU ister), Zen ise bulutta
> hızlı ve ücretsizdir. Bu proje "embedding local (gizlilik) + chat bulut (hız/maliyet)" dengesini
> seçti. Daha sıkı gizlilik istiyorsan LLM'i de Ollama'ya taşıyabilirsin.

---

## Nasıl? (How)

### Gereksinimler

- **Java 25** (sdkman: `sdk install java 25.0.3-tem`)
- **Maven 3.9+**
- **Docker + docker-compose** (chroma ve pgvector profilleri için)
- **Ollama** (embedding modeli; chat Zen'den geldiği için chat tarafında Ollama gerekmez)

### 1) Ollama'yı kur ve embedding modelini indir

```bash
# Linux/macOS: tek komut (sistem yöneticisi şifresi ister)
curl -fsSL https://ollama.com/install.sh | sh

# Embedding modeli (CPU'da çalışır; 768 boyutlu vektör üretir)
ollama pull nomic-embed-text

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
# {"storeType":"SimpleVectorStore","filesRead":156,"chunksWritten":995,"skipped":[]}
```

İndeksleme sırasında her chunk Ollama ile embedding'e çevrilir — CPU'da tek bir full-ingest
~10-12 dk sürer (vault ~360K token, ~1000 chunk). `simple` profilde sonuç
`data/simple-vector-store.json` dosyasına kaydedilir; yeniden başlatınca otomatik yüklenir
(tekrar indekslemene gerek yok).

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
# {"storeType":"SimpleVectorStore","filesRead":156,"chunksWritten":995}
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
| API | REST controller | `web/AiController.java` |

---

## Vector Store Karşılaştırması

| | `simple` (varsayılan) | `chroma` | `pgvector` |
|---|---|---|---|
| Amaç | POC / local test | Hafif ayrı vektör DB | Postgres içinde vektör |
| Servis | Yok (JSON dosyası) | Docker `pva-chroma:8000` | Docker `pva-pgvector:5433` |
| Metadata filtreleme | Sınırlı | Evet | Evet |
| Kalıcılık | `data/simple-vector-store.json` | `chroma-data` volume | `pgvector-data` volume |
| Arama | Brute-force (küçük veri için yeterli) | HNSW | HNSW (`vector_cosine_ops`) |
| Üretime uygunluk | ❌ | Kısmen | ✅ |
| Aktivasyon | `--spring.profiles.active=simple` | `...=chroma` | `...=pgvector` |

**Öneri:** Önce `simple` ile akışı doğrula → sonra `pgvector` (üretim kalitesi) → dilersen `chroma` ile
kıyasla. Hepsi aynı `VectorStore` arayüzünü kullandığı için kod değişmez.

> **PGVector dims uyarısı:** `application-pgvector.yml` içindeki `app.vector-store.pgvector.dimensions`
> embedding modelinin boyutuna uymalıdır: `nomic-embed-text` → 768, `mxbai-embed-large` → 1024.
> Model değiştirirsen tabloyu yeniden kurmak gerekir (`docker-compose down -v`).

---

## API Referansı

| Metot | Path | Açıklama |
|---|---|---|
| `POST` | `/api/ingest` | Vault'u tarar, chunk'lar, embed eder, store'a yazar |
| `POST` | `/api/chat` | `{"message":"..."}` → `{answer, sources[]}` |
| `POST` | `/api/chat/stream` | Aynı chat, SSE (`text/event-stream`) token akışı |
| `GET` | `/api/stores` | Aktif store tipi + indeks istatistikleri |
| `GET` | `/actuator/health` | Uygulama sağlığı |

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
| `spring.ai.ollama.embedding.model` | `nomic-embed-text` | Embedding modeli (hazırlık + sorgu) |
| `spring.ai.openai.base-url` | `https://opencode.ai/zen/v1` | Zen endpoint (yalnız `zen` profili; env: `ZEN_BASE_URL`) |
| `spring.ai.openai.chat.options.model` | `big-pickle` | Zen chat modeli (yalnız `zen` profili; env: `ZEN_CHAT_MODEL`) |
| `app.vector-store.simple.file` | `data/simple-vector-store.json` | Simple store kalıcılık dosyası |
| `app.vector-store.chroma.url` | `http://localhost:8000` | Chroma adresi |
| `app.vector-store.chroma.collection` | `personal-vault` | Chroma koleksiyonu |
| `spring.datasource.*` | localhost:5433/postgres | PGVector bağlantısı (yalnız `pgvector` profili) |

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
        model: nomic-embed-text
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

| Terim | Açıklama |
|---|---|
| **LLM** | Büyük dil modeli; metinden metin üretir. Bu projede chat modeli = Zen `big-pickle` (bulut). |
| **Embedding modeli** | Metni anlamsal vektöre çeviren model (Ollama `nomic-embed-text`, local). Embedding'ten LLM (Zen) sorumlu değildir. |
| **RAG** | Retrieval-Augmented Generation: dış veriden getirilen bağlamla cevap üretme. |
| **Embedding** | Metni anlamsal vektöre çevirme; benzer anlam = benzer vektör yönü. |
| **Vector store** | Vektörleri ve metinlerini saklayan, "en yakın" vektörü arayan veri deposu. |
| **Chunk** | Dokümanın bölündüğü küçük parça; embedding ve retrieval birimi. |
| **topK** | Soruya en benzer kaç chunk'ın getirileceği. |
| **Hallucination** | Modelin veriye dayanmadan uydurması. RAG bunu azaltır. |
| **System prompt** | LLM'e her istekten önce verilen görev tanımı + davranış kuralları (prolog); kullanıcı sorularından ayrıdır. |
| **Retrieval** | RAG'ın "getir" adımı: soruyu vektöre çevirip store'da en yakın chunk'ları bulma. |
| **Spring AI** | Java/Spring için AI uygulama çatısı (modeller, vector store'lar, advisor'lar). |
| **ChatClient** | Spring AI'nın fluently chat API'si (prompt → call/stream → cevap). |
| **Advisor** | ChatClient'a takılan ara katman; `QuestionAnswerAdvisor` RAG'i uygular. |
| **MCP** | Model Context Protocol: LLM'lerin harici araç/veriyle standart protokolle konuşması. Spring AI 2.0'da çekirdeğe taşındı (bu proje henüz kullanmıyor). |

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
