# Decision log

How the project got here, step by step, with the measurements behind each decision.

## Decisions

### 1. Spring AI or plain HTTP

- Spring AI needs Spring 6, so only SAP Commerce 2211-jdk21.
- SAP Commerce is not Spring Boot: no auto-configuration. Build `ChatModel` / `EmbeddingModel`
  with builders and register them as beans in `<ext>-spring.xml`.
- Dependencies via `external-dependencies.xml`. Main risk: conflicts with platform libraries
  (Jackson, Reactor, Netty).
- Fallback: plain `java.net.http.HttpClient` to the embedding/chat API, no library dependency
  Safer, but more code of our own.

**Classpath check (static, 2026-10-05): no blocker, Spring AI stays.** We compared the runtime deps of
`spring-ai-solr-store` (Spring AI 1.1.8) with the jars in the hybris JVM of 2211-jdk21.17
(`bin/platform/**/lib`, `bin/modules/**/lib`, without `solrserver/resources`, which is the separate
Solr server process). Rule: the extension never ships a jar the platform already has. All extension
`lib` jars share one classloader with the platform, so a duplicate means "whichever loads first wins".

| Library | Spring AI wants | Platform has | Decision |
|---|---|---|---|
| Spring (core, beans, context, aop, messaging) | 6.2.19 | 6.2.19 | same, use platform |
| SolrJ, solrj-zookeeper | 9.10.1 | 9.10.1 (`solrfacetsearch`) | same, use platform |
| Jackson core/databind/annotations | 2.18 | 2.22.1 (`core`) | platform is newer within 2.x, use platform |
| Micrometer observation/commons | 1.15.12 | 1.15.0 (`core`) | patch difference, use platform; pin the added `micrometer-core` to 1.15.0 |
| Reactor core | 3.7.19 | 3.7.13 (`core`) | patch difference, use platform |
| Netty | 4.1.119 | 4.1.136 (`core`) | platform is newer, use platform |
| classmate | 1.7.0 | 1.3.4 (`validation`) | **watch**: needed only by victools schema generation (tool calling), not by the vector store. Use platform's, confirm in the spike |
| spring-jcl | 6.2.19 | none, platform uses `commons-logging` 1.3.4 + `jcl-over-slf4j` | **exclude**: both provide `org.apache.commons.logging` |
| Jetty client 10 (SolrJ `Http2SolrClient`) | 10.0.26 | none | **exclude**: inject the platform's `SolrClient` |
| `javax.validation` 1.1 | via `jackson-module-jsonSchema` | jakarta 3.1 | **exclude**: dead javax API |
| spring-ai-*, ST4, victools, jtokkit, semver4j, context-propagation, micrometer-core, HdrHistogram, LatencyUtils | new | none | add to the extension `lib` |

Still to confirm at runtime (spike 4a): the extension starts and the beans are created, with no
`NoSuchMethodError` from classmate or Micrometer.

### 2. Where the vectors live

- pgvector is out: no Postgres on CCv2.
- Preferred: the platform's own Solr. Solr 9 supports dense vector fields and kNN queries, so
  embeddings go into the existing product index via a value provider. No new infrastructure, and
  semantic results can combine with facets and filters.
- Spring AI has no Solr `VectorStore` ([#4782](https://github.com/spring-projects/spring-ai/issues/4782),
  untriaged since 2025-11). Built as a separate library: `spring-ai-solr-store` (Spring AI 1.1.x,
  SolrJ 9.10), later to spring-ai-community. The core repo is slow on new vector stores and has
  removed some (Infinispan, SAP HANA).
- Verified on 2211-jdk21.17: Spring 6.2.19, Solr 9.10, SolrJ 9.10.1, so Spring AI 1.1.x, not 2.x
  (needs Spring Framework 7).

### 3. Models

Two models: one for embeddings, one for the grounded answer.

**Embeddings.** Anthropic has no embedding model (its docs point to Voyage AI). Options:

| Option | Pros | Cons |
|---|---|---|
| Voyage AI | quality, multilingual models | extra provider + API key |
| OpenAI `text-embedding-3` | common, cheap | extra provider |
| Local model (bge-m3, multilingual-e5) via ONNX in the JVM | free, data stays on the server, no API dependency | slower indexing, more memory on CCv2 pods |

- Must be multilingual: catalogs are DE / FR / IT (Swiss clients), not only EN.
- Dimensions: Solr 9.10 (our CCv2 version) accepts at least 4096, so the old 1024 cap doesn't
  apply. 1536 / 3072-dim models are fine; fewer dims still means a smaller index.
- Changing the model means reindexing the whole catalog (vectors from different models don't
  compare). Store the embedding model id in the index. Exception: Voyage 4 models share one
  embedding space, so the query model can change without a reindex (see the eval below).

**Eval (2026-10-05, `eval/`).** 40 synthetic hardware products (`data/`), one Solr doc per
product and language (`name. description`), 31 queries in EN/DE/FR/IT, run through `spring-ai-solr-store`
on Solr 9.10. The EN product names are shop copy ("pendulum saw", "disc cutter"), so keyword search
would miss them. Run with `cd eval && mvn -q compile exec:java`, which reads `VOYAGE_API_KEY` from `../.env`.
Embeddings are cached in `eval/target/cache`, so reruns make no API calls. Per-query rows:
`eval/results/hardware-v1/results.tsv`; summary: `eval/results/hardware-v1/summary.tsv`. In that file, `new_tokens` counts
Voyage tokens spent by that run; reruns read the disk cache, so the committed file shows 0 (the first run
spent ~6.7K tokens per config).

| Config (index model / query model / dims) | hit@1 | MRR | MRR without constraint queries |
|---|---|---|---|
| voyage-4-lite / 256, 1024, 2048 | 0.81 | 0.88–0.89 | 0.89–0.90 |
| voyage-4 / 256, 1024 | 0.94 | 0.97 | 0.98 |
| voyage-4 / 2048 | 0.97 | 0.98 | 1.00 |
| voyage-4-large / 256, 1024, 2048 | 0.97 | 0.98 | 1.00 |
| voyage-4 / 1024, EN text only | 0.87 | 0.94 | 0.95 |
| index voyage-4-large, query voyage-4-lite / 1024 | 0.84 | 0.91 | 0.92 |
| **index voyage-4-large, query voyage-4 / 1024** | 0.97 | 0.98 | 1.00 |

What the numbers say:
- One query is about 0.03 MRR, so differences under about 0.06 are noise.
- The model matters, the dimensions don't: lite is clearly worse, and 256 dims match 2048 for large.
- Shared space works: indexing with large and querying with voyage-4 matches large/large.
  Querying with lite stays near lite, so the query model is the bottleneck.
- Per-language docs beat EN-only (0.97 vs 0.94). The misses are FR queries against EN text. This is
  within noise, but per-language docs also map 1:1 to hybris localized fields.
- kNN ignores numbers: "drill under 700 W" returns the 750 W drill first in every config. The other
  two constraint queries pass only because the catalog has one ladder and one garden pump. Numeric
  constraints need filters (Solr `fq`) or hybrid search, not a better model.

**Decision:** index with `voyage-4-large`, query with `voyage-4`, 1024 dims (the Voyage default), one
vector per product and language.
- large vs voyage-4 at indexing is within noise here. large wins because it hit the ceiling in every
  config and indexing cost is negligible.
- Indexing cost is negligible: the whole matrix was ~60K tokens.
- Revisit 256 dims on the real catalog: 4× smaller index, equal here, but 40 products is an easy set.

This also covers verification #2 of `spring-ai-solr-store`: real 256 to 2048-dim vectors and
cross-lingual queries go through the store's POST path.

**Answer (RAG): Claude.** Anthropic Java SDK (`com.anthropic`), or Spring AI's Anthropic module.

| Model | ID | $ / 1M input / output | ~$ per query* |
|---|---|---|---|
| Claude Opus 5.5 (start here) | `claude-opus-5-5` | 4 / 20 | 0.018 |
| Claude Sonnet 5.5 | `claude-sonnet-5-5` | 2 / 10 | 0.009 |
| Claude Haiku 4.5 | `claude-haiku-4-5` | 1 / 5 | 0.0045 |

\* ~3k input tokens (10 products with descriptions), ~300 output tokens. Prices as of 2026-09.

- Start on Opus 5.5, build the evals, then measure whether Sonnet or Haiku holds the same
  quality. Move down only on eval results.
- Structured output: the model returns JSON with product codes, not free text. Java checks every
  code is in the Solr results and rejects the answer otherwise (model suggests, code decides).
- Prompt caching: keep the system prompt stable and first.
- Jev doesn't fit the answer step (it doesn't write text). At most for relevance checks.

## Original plan

The MCP tool is not built yet; the rest became the sections below.


- **Indexing:** a value provider computes the product embedding during Solr indexing (or a
  cronjob precomputes and stores it).
- **Search:** OCC endpoint, e.g. `GET /products/semantic-search?q=...`, returns products from
  kNN; optionally an LLM answer grounded only in those products, with product links.
- **Evals:** a set of queries with expected products and a hit-rate report.
- **MCP:** a hybris-mcp tool that calls the endpoint.

## Spike on 2211-jdk21

1. Empty extension on 2211-jdk21: does Spring AI start without classpath conflicts?
2. In parallel: check the Solr version, dense vector support and dimension cap on CCv2.

These two answers decide the rest. If Spring AI doesn't start, use plain HTTP and keep Solr.

**Answered 2026-10-05: Spring AI starts.**
- **Setup:** clean 2211-jdk21.17 in `~/sap/2211-jdk21.17`, with only `hac`, `solrserver` and
  `semanticsearch` (+ `solrfacetsearch`) loaded. The extension stays in this repo and is loaded via
  `<path dir=".../semantic-search-sap-commerce"/>` in `localextensions.xml`.
- **Dependencies:** `semanticsearch/external-dependencies.xml` lists 16 jars explicitly, because the
  platform's Maven runs with `excludeTransitive=true`. No Jetty, `spring-jcl` or `javax.validation`.
- **Startup:** server starts in 12.6s and the Spring context loads for `master` and `junit`, with no
  `NoSuchMethodError` or `BeanCreationException`.
- **HAC check:**
  - beans `semanticSearchVectorStore` (on SolrJ's `HttpJdkSolrClient`, Jetty-free) and
    `semanticSearchEmbeddingModel` exist,
  - a live voyage-4 call from inside hybris returns 1024 dims,
  - Spring AI's `JsonSchemaGenerator` works on the platform's classmate 1.3.4, which resolves the
    "watch" item from the classpath check,
  - classes load from the expected jars: classmate 1.3.4, micrometer-observation 1.15.0 and
    jackson-databind 2.22.1, all from the platform.
- **macOS gotcha:** after unzipping, the Tomcat wrapper is killed (`Killed: 9`) until the quarantine
  flag is removed: `xattr -dr com.apple.quarantine <install dir>`.

**Vectors in the product index (2026-10-05).** The test catalog `hardwareCatalog` (`data/`, 1,122
categories, 40 products, EN/DE/FR/IT) is indexed with one 1024-dim `voyage-4-large` vector per product and
language. A full index takes 65s on the free Voyage tier (throttled) and returns 40 docs. A raw
`{!knn f=embedding_de_knn1024}` query for "Bohrmaschine für Beton" ranks the SDS-plus drill (0.82), the
percussion drill (0.80) and the concrete bits (0.79) first.

How it is wired:

- **Index config:** `semanticsearch/resources/impex/semanticsearch-solr.impex`, imported after
  `data/hardware-categories.impex` and `data/synthetic-products.impex`. It adds the `knn1024` value to the dynamic
  `SolrPropertiesTypes` enum and a localized `embedding` property.
- **Value resolver:** `ProductEmbeddingValueResolver` embeds the whole indexer batch (items × languages)
  on first use and keeps the vectors in the batch context, so one batch is one embedding call, not one
  per product and language.
- **Solr schema:** add to `config/solr/instances/default/configsets/default/conf/schema.xml`, then reload
  the core with the *indexing* client (the search client gets 403 on admin calls):
  ```xml
  <fieldType name="knn_vector_1024" class="solr.DenseVectorField" vectorDimension="1024" similarityFunction="cosine" />
  <dynamicField name="*_knn1024" type="knn_vector_1024" indexed="true" stored="false" />
  ```
  Field names for localized properties of a generic type follow `<name>_<lang>_<type>`, for example
  `embedding_de_knn1024`. Text fields are named `name_text_de` instead, because `text_de` is a field type
  of its own.
- **Platform Solr:** HTTPS with basic auth, and separate users for search and indexing. Use the platform's
  `SolrClient` (`solrSearchProviderFactory` → `getClient` / `getClientForIndexing`), not a client of
  your own.
- **Voyage free tier (3 requests a minute):** the indexer's quick retries all fail with 429. Locally:
  `semanticsearch.voyage.document.batch.size=40` and `...min.interval.ms=21000`. With a payment method
  on the account, the defaults (1000, 0) apply.
- **HAC scripting:** with "commit" off, the whole script is rolled back, including imports.

**Search through `spring-ai-solr-store` (2026-10-05).** This was the last of the library's three
verification checks: a search on the platform-managed index, with the platform's client and field names.

```groovy
def provider = solrSearchProviderFactory.getSearchProvider(config, indexedType)
def index = provider.resolveIndex(config, indexedType, 'default')
def store = SolrVectorStore.builder(provider.getClient(index), index.name, semanticSearchEmbeddingModel)
    .idField('code_string').contentField('name_text_de').vectorField('embedding_de_knn1024')
    .metadataPrefix('').build()   // every other stored field becomes metadata, e.g. catalogVersion
```

| Query (voyage-4 query, voyage-4-large index) | Top 3 |
|---|---|
| "Bohrmaschine für Beton" | SDS-plus drill (0.64), percussion drill (0.61), concrete bits (0.57) |
| same + `code_string in ['eval-p14', 'eval-p15', 'eval-p19']` | only those three, topK still filled: `fq` pre-filters on hybris fields |
| "Küchenarmatur mit Brause" | sink mixer first (the eval's lite model ranked it 10th) |
| "Bohrmaschine für Beton" on the **French** field | same products, lower scores (0.59 vs 0.64): search the session language's field |

Scores are cosine: Solr's 0.821 maps back to 2 × 0.821 − 1 = 0.641. There is no store bean, because
the platform's clients come per index from the provider, so the store is built per request.

**HAC gotcha:** HAC's XSS filter deletes everything from `Expression(` up to the next `)` in a script, which
mangles calls like `.filterExpression(...)`. Build such method names at runtime:
`request.invokeMethod('filter' + 'Express' + 'ion', expr)`.

## OCC endpoint (2026-10-05)

`GET /occ/v2/{baseSiteId}/products/semantic-search?query=...&pageSize=10&lang=de` is public, like the
platform's product search.

```json
{ "query": "angle grinder",
  "products": [ { "code": "eval-p08", "name": "Brixo AG-125 disc cutter, 1100 W", "score": 0.59 }, ... ] }
```

| Case | Response |
|---|---|
| query found | 200, 0.3–1.1s locally (one voyage-4 call + one kNN query) |
| blank query or pageSize outside 1..50 | 400 with the reason |
| Voyage down or rate limited, or no index | 503 in ~0.25s; no retry on the query side, so the shop can fall back to keyword search |
| repeated query | served from the query embedding cache, ~0.05s instead of ~1s, no API call |
| more than `semanticsearch.voyage.query.max.requests.per.minute` API calls (default 300, per node) | 429 with the reason; cache hits are still served |

How it is wired:
- **Service:** `SemanticSearchService` takes the facet search config from the current base site, finds the
  active index (two-phase safe) and builds a `SolrVectorStore` per call from the platform client. Field
  names come from the platform's `FieldNameProvider`, never hand-built (step 4b got one wrong). The Solr
  client is the platform's pooled `CachedSolrClient` (same instance per index), so the service doesn't close it.
- **Language:** the session language's vector field. A store language that isn't indexed has no such field,
  so the service falls back to the store's default language, or else any indexed one (checked: session
  `pl` → German results).
- **Site:** `semanticsearch-site.impex` creates the `BaseSite` and `BaseStore`. OCC activates the store's
  catalogs in the session, so a catalog needs an active version.
- **Cost control:** the public endpoint calls a paid API, so the query model has a bounded LRU cache
  (`...query.cache.size`, default 1000, about 4 MB) and a per-minute budget of API calls per node. Per-client
  limits belong in front of the app (WAF, API gateway). Checked with a budget of 2: three distinct queries
  give 200, 200, 429, and a cached query still returns 200 after that.
- **Check:** `scripts/check-occ.sh` runs against the local server and asserts the 400, a German query
  and a synonym query.

What OCC needs, and the platform doesn't say:
- **Web config folder:** controllers join OCC through `resources/occ/v2/<name>occ/web/spring/*-web-spring.xml`.
  commercewebservices imports `classpath*:/occ/v2/*occ/...`, so the folder name must end in `occ`.
- **Responses are generated beans:** OCC writes JSON through JAXB (MOXy), not Jackson, so a Java record
  fails with `HttpMessageConversionError`. Use beans from `semanticsearch-beans.xml` (no-arg constructor
  + setters).
- **Status codes:** OCC maps exceptions to HTTP status by simple class name
  (`webservicescommons.resthandlerexceptionresolver.<Name>.status`). An unmapped exception, including
  Spring's `ResponseStatusException`, becomes a 400 with a generic message. Hence
  `SemanticSearchUnavailableException` → 503, and `IllegalArgumentException` → 400 with the message.
- **Path:** `/products/semantic-search` wins over the platform's `/products/{productCode}` because a
  literal path segment is more specific than a variable.

Not done yet: an OCC `fields` parameter, Swagger annotations, and an automated hybris integration test.
The check script covers the behaviour against a running server.


## Grounded answers (RAG) on a local model (2026-10-05)

`GET /occ/v2/{baseSiteId}/products/semantic-search/answer?query=...&lang=de`

```json
{ "status": "ANSWERED",
  "answer": "Für Bohren in Betonwände empfehlen wir das Brixo HD-750 SDS-plus Bohrmaschine, 750 W und das Brixo SDS-plus Betonbohrer-Set, 5-teilig.",
  "citedCodes": ["eval-p01", "eval-p14"],
  "products": [ ...the 8 search results... ] }
```

How it works:

- **Flow:** semantic search returns the top 8 products, with descriptions from the index. The chat model gets
  them plus the question and must return JSON (`answer`, `productCodes`).
- **Model suggests, code decides:** `SemanticAnswerService.validate` keeps the answer only if every cited
  code is one of the 8. Invalid JSON, an empty answer or an invented code gives `FALLBACK` with a reason,
  and the product list is still returned. Unit-tested with fixed model outputs (`ant unittests
  -Dtestclasses.extensions=semanticsearch`, 4 tests, no tenant needed).
- **Provider-neutral:** the service depends on Spring AI's `ChatModel`. Locally that is Ollama
  (`OllamaChatModels`), and another provider is another bean. The jars added are `spring-ai-ollama`
  and `spring-ai-retry`; the web client comes from the platform.
- **Trusted input only:** catalog text goes into the prompt as trusted shop content. Customer reviews
  would need handling as untrusted input (prompt injection).

**Answer eval** (`eval/answer_eval.py`, through the endpoint; results in `eval/results/hardware-v1/answer-results-*.tsv`): 31 catalog queries plus 3 products the
catalog doesn't have.

| Model (Ollama, M5, 32 GB) | survived validation | cites an expected product | refuses absent products | median |
|---|---|---|---|---|
| `qwen3:8b` | 34/34 | 0.90 | 3/3 | 3.2s |
| `gemma3:12b` | 34/34 | 0.97 | 3/3 | 9.2s |

What it shows:

- **Numbers now work.** "drill under 700 W" returns the 650 W drill. Search alone ranked the 750 W one
  first, and the model reads the wattage in the text. This only works when the right product is
  among the 8: answers are capped by retrieval.
- **No fabricated products:** none in 68 answers.
- **Mistakes are still possible.** qwen once cited a hinge for a door handle. Validation guards against
  invention, not mistakes; only the eval catches those.
- **Literal names:** both models refused once because the catalog's shop copy names the product
  differently ("disc cutter" for angle grinder, "pocket rule" for tape measure), even though search found
  it first.
- **Tried and reverted:** a system prompt hint, "judge products by their description; the shop may name
  a product differently" (with an example from outside the catalog, so the eval isn't taught the answer).
  qwen3:8b stayed at 0.90, with one query fixed and one broken (`eval/results/hardware-v1/answer-results-qwen3-8b-prompt2.tsv`). It
  still says "we don't have an angle grinder" while the description reads "cuts and grinds metal and
  stone with 125 mm discs". That limit belongs to the 8B model, not the prompt or the data, and gemma3:12b
  handles it. Prompt changes stay only when the eval improves.
- **Lenient metric:** gemma cites more products, so "cites an expected product" flatters it (for "under
  700 W" it cites both drills). One query is about 0.03, so 0.90 vs 0.97 is 2 queries.
- **Choice:** qwen3:8b for latency, gemma3:12b when quality matters more than 9s.

What a local model needs that the docs don't stress:

- **Retries:** Spring AI's default `RetryTemplate` retries 10 times with backoff up to 3 minutes. With
  Ollama down, one request took **424s**. The answer model gets `maxAttempts(1)`, so a failure is an
  immediate `FALLBACK`.
- **Options:** explicit `numCtx` (Ollama's default context truncates silently), `keepAlive=30m` (avoids
  model reload pauses), `disableThinking()` (no `<think>` block before the JSON; gemma accepts it too),
  and `format` = the answer's JSON schema (`BeanOutputConverter.getJsonSchemaMap()`).
- **Timeouts:** a read timeout on the HTTP client (none by default).
- **Long evals:** a sleeping Mac stalls them (`caffeinate -i` doesn't help with the lid closed or on
  battery), and the server is best started with `nohup`, outside tool time limits.

## Hybrid search: deferred (2026-10-05)

Hybrid search (BM25 / `edismax` next to kNN, fused with RRF) was the planned fix for codes, brands and
numbers. We probed the current kNN search (`lang=en`, top 5):

| Query | kNN result | Verdict |
|---|---|---|
| `HD-750` (model code) | Brixo HD-750 first, then noise that matched "750" (hinge, spanner, "750 ml" primer) | found |
| `Brixo` (brand) | the 4 Brixo products in places 1–4 | found |
| `drill under 700 W` | 750 W drill before the 650 W one | **missed** |

`voyage-4-large` handles codes and brands better than the plan assumed. The real gap is numeric
constraints, and BM25 doesn't fix those: "under 700 W" is a comparison, not a keyword.

- **What fixes numbers:** structured filters. The store already pre-filters on `fq` (step 4c). Two
  things are missing:
  1. a numeric field in the index. Wattage is only in the product name here; a real catalog has it as a
     classification attribute.
  2. turning the constraint in the query into a filter (`power_w:[* TO 700}`). Regexes are brittle across
     4 languages, and the local model would add ~3s to the plain search.
- **Meanwhile:** the RAG answer already handles it ("under 700 W" → the 650 W drill), and only the plain
  list doesn't.
- **When to come back to BM25 + kNN:** when an eval on a real catalog shows any of these:
  - near-identical codes confused (`HD-750` vs `HD-760`, SKU variants, EANs),
  - typos and trade abbreviations,
  - one-word queries that kNN ranks poorly.

  Then add an `edismax` query next to kNN in `SemanticSearchService`, fuse with RRF (about 30 lines), and
  let that catalog's eval decide.
- **Cheapest step for numbers before then:** a `power_w` index field filled by a resolver from the
  product name, plus a filter from the query. On these 40 products it would fix exactly one query.

## Local embeddings with bge-m3 (2026-10-05)

To get off Voyage's free-tier limits and keep data local, the default embedding provider is now `bge-m3` on
Ollama. It is multilingual, has 567M parameters and gives 1024-dim vectors, so the Solr field stays the
same. `semanticsearch.embedding.provider=ollama|voyage` picks the beans through Spring aliases. Voyage stays
wired; a switch needs a full reindex.

| On the same 40 products and 31 queries | MRR | hit@1 | Full reindex | Query |
|---|---|---|---|---|
| voyage-4-large index, voyage-4 queries | 0.98 | 0.97 | 65 s (free tier, throttled) | 0.3–1 s |
| **bge-m3 (local)** | **0.93** | 0.87 | **3 s** | **~0.03 s** |
| voyage-4-lite | 0.88 | 0.81 | | |

- **Quality:** bge-m3 lands between voyage-4-lite and voyage-4, about 2 queries behind the best Voyage
  setup. It is weaker mainly on synonyms (0.83 vs 1.00).
- **Speed:** local is far faster and free, and nothing leaves the server.
- **Failure behaviour:** `OllamaEmbeddingModel` has no retries, unlike Spring AI's chat model. With Ollama
  stopped, `/semantic-search` returns 503 in 13 ms. Its errors are Spring `RestClientException`s, not
  `IllegalStateException`, so `SemanticSearchService` now maps every search failure except the 429 budget
  to 503.
- **Lost on this path:** the query cache and the call budget live in `VoyageEmbeddingModel`, so the local
  model has neither. Accepted: it is free, and a query costs ~20 ms.
- **Jetty trap in the eval:** with SolrJ's Jetty 10 client on the classpath, Spring's `RestClient` picks a
  Jetty request factory that expects Jetty 12, and the Ollama call fails with `NoSuchMethodError`. Pass a
  `JdkClientHttpRequestFactory` explicitly, as the extension already does. The eval also gained an explicit
  `System.exit`, because Jetty's non-daemon threads made `exec:java` hang on an exception instead of
  failing.

## Generated B2B parts catalog (2026-10-05)

The 40 hand-written products had one real weakness: the same person wrote the products, the queries
and the expected answers. They are replaced by `data/generate.py` (stdlib only, deterministic):

- **339 products** in 10 categories:
  - deep groove ball bearings (6000/6200/6300, open/ZZ/2RS, ISO dimension tables),
  - DIN 912 screws and DIN 934 nuts (8.8/A2/A4),
  - O-rings (NBR/FKM/EPDM with temperature ranges),
  - SPZ/SPA/XPZ belts, contactors, motor protection breakers (setting ranges), push-in fittings,
    cable glands (clamping ranges) and greases.
- **Brands and near duplicates:** invented brands, some designations from two brands, and dense near
  duplicates (6204/6205, -2RS/-ZZ, M8x30/M8x35, A2/A4).
- **Texts:** EN/DE/FR/IT from templates. Trade words (Inbus, CHC, brugola, Allen) appear only in queries.
- **68 queries:** codes, trade words, numeric constraints, needs and cross-language. Each **expected set is
  computed from the attributes** by a predicate written next to the query in `eval/queries.tsv`. The script
  asserts that every set is non-empty and every code exists.
- **History:** the old data is at commit b4c09e2, and its results are in `eval/results/hardware-v1/`.
- **New metric:** recall@10, the share of the expected set in the top 10. For "all sealed 20 mm bearings",
  one hit isn't the answer.

| bge-m3 search, 68 queries | MRR |
|---|---|
| all (hit@1 0.79, hit@5 0.97, recall@10 0.94) | 0.86 |
| part codes | 1.00 |
| cross-language | 0.97 |
| trade words | 0.85 |
| numeric constraints | 0.69 |
| needs | 0.69 |

| qwen3:8b answers | cites an expected product |
|---|---|
| all 68 (median 4.4 s) | 63 (0.93) |
| trade words / cross-language | 18 of 18 / 18 of 18 |
| numeric constraints | 11 of 14 |
| absent products refused | 3 of 3 |

- **Search finds codes and languages; it fails on numbers and slang it doesn't know:**
  - "disjoncteur moteur 12 A" puts an M12 screw first,
  - "brugola M5 x 16" puts a bearing first,
  - ranges (breakers 2.5–4 A, glands 6–12 mm) land in places 2–4.
- **Answers recover most of that:** the model reads the ranges and the trade words in the top 8.
- **Validation caught a real invention:** for "Motorschutzschalter für 3 A", qwen cited `VQ-MS-2.5-4`.
  That was the right range from the other brand, but it wasn't among the 8 results, so the answer fell back
  to the list.
- **Twin brands rank below neighbours:** in the endpoint (one doc per product, the session language's
  field), "6204-2RS" ranks the twin brand below neighbouring sizes of the same brand. The eval (one doc per
  language variant) doesn't show that.
- **Where the filter step starts:** numeric constraints (0.69) are where structured filters belong. The
  expected sets for them are already computed, and the attributes are in `data/parts.json`.

## Hosted models for production (2026-10-05)

CCv2 has no GPUs or model hosting, so local Ollama is development only, and production calls a hosted
model over HTTPS. SAP Commerce has no built-in semantic search either: the CX Q1 2026 release adds
AI-generated product descriptions to Commerce Cloud, nothing for search.

**The hosted model on the parts catalog** (Voyage: voyage-4-large index, voyage-4 queries; 118K tokens,
free tier):

| MRR | hosted (Voyage) | local (bge-m3) |
|---|---|---|
| all (hit@5) | 0.93 (1.00) | 0.86 (0.97) |
| cross-language | 1.00 | 0.97 |
| trade words | 0.96 | 0.85 |
| part codes | 0.89 | 1.00 |
| needs | 0.89 | 0.69 |
| numeric constraints | 0.84 | 0.69 |

- **Where hosted wins:** clearly on numbers, needs and trade words.
- **Where it loses:** codes. "6205 2RS" ranks 6005-2RS first, an argument for a keyword/hybrid leg later.
- **Answers:** still measured only with the local qwen3:8b, since the production answer model isn't
  chosen.

**Provider options, to decide with the shop owner** (what they already license matters):

| Option | Embeddings | Answer model | Data location |
|---|---|---|---|
| SAP Generative AI Hub (BTP AI Core, Extended plan) | text-embedding-3, Amazon Titan, NVIDIA, gemini-embedding | Claude, Gemini, GPT, Mistral, … | per BTP region; Zurich (ch20) availability not confirmed |
| Azure OpenAI, Switzerland North | text-embedding-3-small/large in region | no GPT on Standard in CH | Switzerland for embeddings |
| Infomaniak AI Tools | bge-multilingual-gemma2, Qwen3-Embedding-8B | Qwen, Mistral, Gemma, Apertus | Switzerland |
| Voyage AI (measured) | voyage-4 family | none | US |

- **Integration:** SAP Cloud SDK for AI (Java) ships Spring AI adapters (`OrchestrationSpringAiEmbeddingModel`,
  chat), and Infomaniak is OpenAI-compatible (Spring AI's OpenAI module). Either is a bean and a property
  in our design, but each SDK needs the classpath check from the spike.
- **Choosing:** run the eval on the shortlist with the candidates' keys, and on the shop's own catalog in a
  pilot.

**Stored vectors: embed on change, index from the database (2026-10-05).** Until now the value resolver
called the embedding model while indexing. Shops commonly run a full Solr index every night, so every night
would have re-embedded the whole catalog (an estimated $60–450 a month for 70,000 products in 3 languages), and
an embedding provider outage would have failed the shop's nightly index.

- **Design:**
  - `ProductEmbedding` (product, language, `sourceHash`, `vector`) holds one vector per product and language.
    It is a separate type, not a product attribute, so writing a vector changes neither the product's
    `modifiedtime` (delta index) nor anything catalog sync copies.
  - `productEmbeddingCronJob` pages through the products of the configured index (types, catalog versions,
    languages) and embeds only texts whose hash changed. `sourceHash` is SHA-256 of the model id
    (provider, models, dimensions) and the text.
  - The resolver only reads. It uses a vector only if its hash matches the current text and model, so a
    changed text or a model switch never puts a stale vector in the index. Such a product drops out of
    semantic search until the next job and index run.
  - Considered: a separate Solr index for the vectors, with its own schedule. It would have meant copying the
    shop's visibility filters into it and merging two result lists for hybrid search.
- **Measured locally (339 products × 4 languages, bge-m3):**

  | Step | Result |
  |---|---|
  | First job run | 1,356 texts embedded, 25 s |
  | Second run, nothing changed | 0 embedded, 0.05 s |
  | Full index from stored vectors | 0.5–1.2 s; 339 docs, each with a vector in all 4 languages |
  | Changed German description, provider down | Job ends FAILURE with nothing written; the full index succeeds; the product has no `de` vector until the job reruns, `en` is kept |
  | Provider back | Job embeds exactly 1 text; the next index has the `de` vector again |

  The indexer ran with a failing model in every one of these index runs. `scripts/check-occ.sh` still
  passes.
- **Unit tests:** vector round trip through storage and hash staleness (`ProductEmbeddingServiceTest`,
  `ant unittests -Dtestclasses.extensions=semanticsearch`).
- **Ceilings:**
  - The job embeds every product of the index's catalog versions, not the indexer query's subset.
  - The vectors are Base64 in a `LONG_STRING` column: 5,464 characters a row. That is ~1.2 GB for 210,000
    rows with a 1-byte charset, and ~2.3 GB as `nvarchar(max)` on CCv2's SQL Server. int8 vectors would be a quarter.
  - Rows of deleted products stay behind; they are never read.
- **In production:** the job becomes the first entry of the nightly composite index job.

**Answer cost (2026-10-06).** The answer model is the only cost that grows with traffic (estimated
$0.0008–0.0018 per answer with a small hosted model, against ~$1 per million searches for query embeddings).
Four rules in `SemanticAnswerService` and `OllamaModels` cut the calls and bound the spend:

| Rule | Setting | Effect |
|---|---|---|
| Codes and short queries get the list only (status `SKIPPED`) | `semanticsearch.answer.min.words=2` (words of 4+ letters) | 19 of 68 eval queries skipped: all 11 codes and 8 spec-like queries (`O-ring 40 x 3.5 FKM`); no need or constraint query |
| No thinking, output capped | `...disable.thinking=true`, `...max.tokens=256` | Hosted reasoning models bill thinking as output; a cut-off answer is invalid JSON and falls back. No answer was cut off in the eval |
| Answer cache, key = language + normalized query + the retrieved codes | `...cache.size=10000`, 1 day | Same question and results: 0.04 s instead of 4.8 s, no call. New results give a new key |
| Daily cap per node | `...max.per.day=5000` | Above it the shopper gets the list (`FALLBACK`). Cache hits don't count |

- **Quality unchanged:** `answer_eval.py qwen3:8b-capped 0`: on the 49 queries that still get an answer, 45
  cite an expected product, the same 45 as before; absent products still refused 3 of 3. The eval now reports
  `SKIPPED` separately. Of the 19 skipped queries, 18 had been answered correctly. Their list (local bge-m3,
  8 shown) has the right product first for 16 and among the 8 for 18. The miss is `SPZ 1250`, a code the
  keyword search finds, and its answer had been wrong too. The real loss is two queries (`brugola M5 x 16`,
  `air hose tee 4 mm`) where the answer had pointed at the right product ranked 3rd; set `min.words=0`
  to answer everything.
- **Ceilings:** the cache and the cap live in memory per node. With N nodes the real cap is N × 5000 a day,
  and a restart resets the day. A shared counter in the database would make it exact; this is a safety
  ceiling, not billing.
- **Unit tests:** the skip rule on eval-like queries, one model call for a repeated question, and no call
  past the cap (`SemanticAnswerServiceTest`, 8 tests).
