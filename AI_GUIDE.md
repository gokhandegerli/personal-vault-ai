# AI Kavram Rehberi

Bu doküman tek bir uygulamaya bağlı olmayan, **genel yapay zekâ / LLM kavramlarını** toplu açıklar.
Somut örnekler için projeye bak: `README.md`.

---

## 1. LLM (Large Language Model) nedir?

LLM, **metinden metin üreten** bir modeldir. Kendisine verilen metni okur ve yine metin üretir.
Bilgisi **eğitim sırasında** öğrenilir: eğitimde milyarlarca kelime gördüğünde, bu kelimelerin
birbiriyle ilişkisini **parametrelere** (milyarlarca sayı/ağırlık) kodlar.

- Çalışma biçimi **autoregressive**'tir: sıradaki en olası token'ı (kelime parçasını) tahmin ederek
  token token metin üretir.
- İç mimarisi **transformer**'dır (attention mekanizması ile kelimeler arası ilişkiyi kurar).
- "Kaç milyar parametre" demek, bilginin saklandığı alanın büyüklüğüdür.
- Önemli: LLM **bir kütüphane değil, bir tahmin makinesidir**. Soruya dosyadan bakıp yanıtlamaz;
  eğitimde öğrendiği kalıplardan en olası yanıtı üretir.

### Parametric hafıza

Bilgi eğitim sırasında modelin içine (ağırlıklara) gömülür. Bu yüzden:

- Modelin güncelliği eğitim kut tarihiyle sınırlıdır.
- Model yeniden eğitilmeden yeni bilgi öğrenmez.
- ChatGPT, Gemini, Abacus gibi online asistanlar bu kategoridedir: "RAG olmadan çalışan LLM" örneğidir.

---

## 2. Embedding nedir?

Metni **anlamsal vektöre** (ör. 768 boyutlu sayı dizisi) çevirme işlemidir.

- Aynı anlamdaki metinler **aynı yöne bakan vektörler** üretir.
- Benzerlik, vektörler arası **cosine benzerliği** ile ölçülür.
- Bu bir **kelime eşleştirme** değildir: "araba" ile "otomobil" farklı kelimeler ama benzer vektörler.
- Embedding işini yapan model, chat modelinden ayrıdır (ör. `nomic-embed-text`, `all-minilm`).
  Chat modeli metin üretir; embedding modeli sayı üretir.

---

## 3. Vector store nedir?

Embedding vektörlerini ve bunların kaynak metinlerini saklayan, "en yakın vektörü" arayan veri deposu.

- Sorgu geldiğinde: soru da embedding modeliyle vektöre çevrilir, store'da **en yakın** `topK` parça
  bulunur (retrieval).
- Bu arama brute-force olabilir (küçük veri) veya `HNSW` gibi dizinlerle ölçeklenir (büyük veri).
- Örnekler: JSON dosyası (POC), Chroma, PGVector, Qdrant, Milvus.

---

## 4. RAG nedir?

RAG = **R**etrieval-**A**ugmented-**G**eneration (Getir-güçlendir-üret).

RAG, **bir yazılım değil, bir akıştır**: LLM'in tek başına bilmediği özel veriyi, model çağrısından
önce dış bellekten getirip prompt'a bağlam olarak ekler.

```
1) Hazırlık (ingest):  dokümanlar ──► chunk'la ──► embed ──► vector store
2) Sorgu:              soru embed edilir ──► store'da en yakın chunk'lar (topK)
                       ──► system prompt + chunk'lar + soru ──► LLM ──► cevap
```

| Aşama | Kim yapar | Ne olur |
|---|---|---|
| İlgili parçaları bulmak | **Vector store** (retrieval) | Soru vektöre çevrilir, en yakın chunk'lar bulunur |
| Bulunanları anlamlı cevaba çevirmek | **LLM** (generation) | Verilen metni okur, token token yeni metin üretir |
| "Bağlam dışına çıkma" kuralı | **System prompt** | Modeli yalnızca verilen bağlama zorlar |

### Non-parametric hafıza

RAG'ın verisi modelin içinde değil, **dış bellek**te (vector store) durur. Bu yüzden:

- Bilgi indekslendiği anda günceldir — modeli yeniden eğitmek gerekmez.
- LLM bilgiyi **aramaz**; arama işini vector store yaptı. LLM bulunmuş ham paragrafları yeniden
  düzenleyip akıcı cevap yazar.
- "Bağlamda yoksa 'bilmiyorum' der" kuralıyla **uydurma (hallucination)** azalır.

### RAG vs LLM chatbot

| | RAG | ChatGPT / Gemini / Abacus |
|---|---|---|
| Veri kaynağı | Non-parametric (dış bellek, taze getirilir) | Parametric (ağırlıklara gömülü, eğitim kut tarihi) |
| Arama | Evet (retrieval) | Genelde hayır |
| Cevap | Yalnızca dokümanlardan | Yalnızca eğitim verisinden |
| Güncellik | İndekslediğin anda | Eğitim tarihiyle sınırlı |

> Bazı online asistanlar "internette gezinme / web browsing" sunar — bu aslında uygulamaya eklenmiş
> bir arama/RAG katmanıdır; çekirdek üretim yine parametriktir.

### Örnek case: "Interview yap" — kutu LLM, içindekiler RAG

RAG ile LLM ayrımını somutlaştıran klasik örnek, kullanıcının "Beni Stack vs Heap konusunda
interview yap" dediği senaryodur. Gelişen akış:

```
[Kullanıcı] "Stack vs Heap interview yap"
   │
   ├─► Retrieval (RAG): soru embed edilir, topK=6 chunk getirilir (kaynak dosyalar)
   │
   └─► LLM: "Soru 1: Stack'in temel özellikleri nelerdir?"   ← bunu LLM yazdı
```

Her turda kullanıcı cevap verir, yeni soru gelir:

| Görülen davranış | Kim üretiyor |
|---|---|
| Yeni soru sorma ("Soru 1: ... nelerdir?") | **LLM** |
| Cevabı onaylama + eksik ekleme ("Doğru. Ek olarak ...") | **LLM** |
| Akışı ilerletme ("Soru 2: Peki Heap'in ...?") | **LLM** |
| Altta listelenen kaynak dosyalar | **RAG** (retrieval) |

**Kural: Kutu = LLM, kutunun içindekiler (kaynaklar) = RAG.** Interview akışının tamamı — soru
sormak, cevabı değerlendirmek, eksikleri eklemek, sonraki soruya geçmek — LLM'in yazdığı metindir;
programlanmış bir state machine değildir. Sistem (RAG) yalnızca her tur için ilgili chunk'ları ve
önceki konuşmayı LLM'e hazırlar. Dikkat: "doğru kabul etme" de LLM'in yargısıdır ve **yanılabilir**
— model kendi sorduğu soruyu unutup çelişebilir; bu, "değerlendirme"nin LLM'in bir özelliği
olduğunun kanıtıdır.

---

## 5. System prompt nedir?

LLM'e her istekte, daha ilk kelimeyi üretmeden önce verilen **görev tanımı + davranış kuralları**
metnidir (prolog).

- Mesaj geçmişinden bağımsızdır, her istekte en önce gönderilir: "sen kimsin, hangi kurallara uyacaksın".
- Kullanıcı sorularıyla karışmaz; modelin zihnini şekillendirir.
- RAG'da kritik rolü: "yalnızca verilen context'e dayan, bağlamda yoksa söyle" kuralıyla uydurmayı azaltır.

---

## 6. Model çalıştırıcılar vs bulut servisler

LLM/embedding modelleri iki şekilde erişilir:

| Yaklaşım | Örnek | Avantaj | Dezavantaj |
|---|---|---|---|
| **Local model çalıştırıcı** | Ollama (LLM ve embedding modeli çalıştırabilir) | Veri makineden çıkmaz, ücretsiz | LLM CPU'da yavaş (GPU ister) |
| **Bulut servis** | OpenAI, Zen, Gemini API | Hızlı, bedava kotalı seçenekler, GPU gerekmez | Veri dışarı gider, ücretli kotalar |

- Ollama gibi bir çalıştırıcı **tek programla iki tür modeli** servis eder: chat modeli (metin→metin)
  ve embedding modeli (metin→vektör).
- Tipik denge: **embedding local** (gizlilik: dokümanların tamamı dışarı çıkmaz), **chat bulut**
  (hız/maliyet). Bulut chat'e yalnızca **soru + retrieve edilen birkaç chunk** gider.
- Embedding'i buluta taşırsan **tüm dokümanlar** dışarı embed edilmek üzere gönderilir.

---

## 7. Terimler

| Terim | Açıklama |
|---|---|
| **LLM** | Büyük dil modeli; metinden metin üretir (autoregressive + transformer). |
| **Embedding modeli** | Metni anlamsal vektöre çevirir; chat modelinden ayrıdır. |
| **Embedding** | Metni vektöre çevirme; benzer anlam = benzer vektör yönü. |
| **Cosine benzerliği** | İki vektörün ne kadar aynı yöne baktığı; retrieval'ın benzerlik ölçüsü. |
| **Vector store** | Vektörleri + metinleri saklayan, en yakın vektörü arayan veri deposu. |
| **Chunk** | Dokümanın bölündüğü küçük parça; embedding ve retrieval birimi. |
| **topK** | Sorguya en benzer kaç chunk'ın getirileceği. |
| **RAG** | Retrieval-Augmented Generation; dış bellekten bağlam getirip cevap üretme akışı. |
| **Retrieval** | RAG'ın "getir" adımı: soruyu vektöre çevirip en yakın chunk'ları bulma. |
| **System prompt** | LLM'e her istekten önce verilen görev tanımı + davranış kuralları. |
| **Parametric hafıza** | Bilginin model ağırlıklarına gömülü olduğu hafıza (ChatGPT, Gemini). |
| **Non-parametric hafıza** | Bilginin dış bellekten taze getirildiği hafıza (RAG). |
| **Hallucination** | Modelin veriye dayanmadan uydurması; RAG + system prompt azaltır. |
| **Transformer** | Attention mekanizmasıyla kelimeler arası ilişkiyi kuran model mimarisi. |
