# Semantic product search for SAP Commerce

An SAP Commerce extension that finds products by meaning instead of keywords, in German, French, Italian and
English. It can also answer a shopper's question using only the shop's own products (RAG).

**Status (October 2026):** a working prototype on SAP Commerce 2211-jdk21.17, measured on a generated
B2B parts catalog (339 products, 68 queries with computed answers). It is not deployed on CCv2 yet (see
[Limits](#limits)).

## What it does

| Request | Result |
|---|---|
| `GET /occ/v2/{site}/products/semantic-search?query=Inbusschraube M6 x 20 Edelstahl&lang=de` | The DIN 912 M6 x 20 stainless screw first. The shop copy never says "Inbus" |
| `GET /occ/v2/{site}/products/semantic-search/answer?query=salvamotore per motore da 20 A&lang=it` | "Il salvamotore adatto per un motore da 20 A è l'interruttore salvamotore 20–25 A." Every cited product code is checked against the search results; an invented one turns the answer into the plain product list |

- **Index:** vectors live in the platform's own Solr product index, one 1024-dim field per language.
  A cronjob embeds only new and changed product texts and stores the vectors in the database; the standard
  indexer copies them. A full index makes no embedding calls, so it costs nothing and still works when the
  embedding provider is down.
- **Embeddings:** a local `bge-m3` on Ollama by default (free, nothing leaves the server), or Voyage AI
  (hosted, more accurate). One property switches between them.
- **Search:**
  - the query is embedded (with a cache), then a kNN query runs in the session language,
  - Solr filters still apply,
  - responses: 400 for bad input, 429 above the cost budget (Voyage), 503 when the embedding model is down.
- **Answers:** any Spring AI `ChatModel` (a local Ollama model here) writes a JSON answer. The code
  validates it and falls back to the product list on any failure.
- **Answer cost:** model calls are the running cost, so codes and short queries get the list only, repeated
  questions reuse the stored answer, output is capped at 256 tokens, and a daily cap per node bounds the spend.

## Results

On the parts catalog: 339 products, 68 queries in 4 languages. Production needs a hosted model, because CCv2
has no model hosting; the local models are the free development setup.

| Search | hosted (Voyage: voyage-4-large index, voyage-4 queries) | local (bge-m3, dev) |
|---|---|---|
| MRR / hit@5 / recall@10 | 0.93 / 1.00 / 0.98 | 0.86 / 0.97 / 0.94 |
| MRR by kind: cross-language, trade words, codes | 1.00, 0.96, 0.89 | 0.97, 0.85, 1.00 |
| MRR by kind: needs, numeric constraints | 0.89, 0.84 | 0.69, 0.69 |

| Answers and runtime | Value |
|---|---|
| Answers citing an expected product (local qwen3:8b) | 0.93 (63 of 68), median 4.4 s; numeric constraints 11 of 14 |
| Answers citing a product outside the results | 0 shown; the one invented code was caught and fell back to the list |
| Model calls saved by the cost rules | 19 of 68 queries (codes, specs) get the list only; a repeated question takes 0.04 s instead of 4.8 s |
| Search latency | 0.3–1 s with Voyage (~0.02 s for a cached query); ~0.03 s with local bge-m3 |
| Embedding job, 339 products in 4 languages | 25 s the first time with local bge-m3; 0.05 s when nothing changed |
| Full index from the stored vectors | 0.5–1.2 s, no embedding calls |

How each number was measured is in the [decision log](docs/decisions.md).

## Repository

| Path | Contents |
|---|---|
| `semanticsearch/` | The SAP Commerce extension: OCC endpoints, the embedding job and value resolver, the services and the impex files |
| `eval/` | Embedding model eval (Java, Solr in Testcontainers) and answer eval (Python, through the endpoint); outputs in `eval/results/` |
| `docs/decisions.md` | Decision log: each step with the measurements behind it |
| `data/` | Generated B2B parts catalog: `generate.py` writes `parts.json`, `parts-catalog.impex` and `eval/queries.tsv` |
| `config/` | Platform config to copy: `localextensions.xml`, `local.properties.example`, the Solr schema additions |
| `scripts/` | `check-occ.sh` (smoke check against a running server), `apply-solr-schema.sh` |

The Solr vector store is a separate library:
[spring-ai-solr-store](https://github.com/Emenowicz/spring-ai-solr-store).

## Quick start (local)

You need:
- the SAP Commerce 2211-jdk21 zip from the SAP Software Center,
- JDK 21 and Maven,
- [Ollama](https://ollama.com) with `bge-m3` (embeddings) and `qwen3:8b` (answers): no key, no cost,
- or, for hosted embeddings, a [Voyage AI](https://www.voyageai.com) key (`semanticsearch.embedding.provider=voyage`).

These steps are distilled from the spike in the [decision log](docs/decisions.md). The whole sequence hasn't been re-run on a fresh install.

1. **Install the vector store library** (it isn't published yet):
   `cd spring-ai-solr-store && mvn install -DskipTests`.
2. **Unzip the platform.** On macOS, also run `xattr -dr com.apple.quarantine <install dir>`, or the Tomcat
   wrapper gets killed.
3. **Create the config:** in `hybris/bin/platform`, run
   `. ./setantenv.sh && ant createConfig -Dinput.template=develop`.
4. **Set the extensions:** copy `config/localextensions.xml` to `hybris/config/` and point its second
   `<path dir>` at this repository.
5. **Set the properties:** append `config/local.properties.example` to `hybris/config/local.properties` and
   uncomment what you change. The defaults need no key; for Voyage, set the provider and the key.
6. **Build:** `ant clean all`.
7. **Add the vector field** to the Solr schema before the first index:
   `scripts/apply-solr-schema.sh hybris/config/solr/instances/default/configsets/default/conf/schema.xml`
   (it adds `config/solr/schema-additions.xml`, is idempotent, and keeps `schema.xml.orig`).
8. **Initialize and start:** `ant initialize`, then `./hybrisserver.sh`.
9. **Import the data:** in HAC (`https://localhost:9002`, admin/nimda), go to Console → ImpEx Import and
   import, in this order:
   1. `data/parts-catalog.impex`
   2. `semanticsearch/resources/impex/semanticsearch-solr.impex`
   3. `semanticsearch/resources/impex/semanticsearch-site.impex`
10. **Embed, then index:** in HAC, go to Console → Scripting, choose Groovy, switch commit on and run the
    embedding job:
    `cronJobService.performCronJob(cronJobService.getCronJob('productEmbeddingCronJob'), true)`
    Then, as a second run (the indexer only sees the vectors once the first run is committed):
    `indexerService.performFullIndex(facetSearchConfigService.getConfiguration('semanticsearchIndex'))`
11. **Try it:** `scripts/check-occ.sh`. For answers, run `ollama serve` and `ollama pull qwen3:8b`, then:
    ```
    curl -k 'https://localhost:9002/occ/v2/semanticsearch/products/semantic-search/answer?query=angle%20grinder&lang=en'
    ```

## Limits

- **Data:** quality is measured on a generated catalog (real standard designations, invented brands and
  texts). A real catalog with messy descriptions will differ.
- **CCv2:** not deployed. Still missing: the Solr schema customization, secrets in Cloud Portal and outbound
  access to the embedding API.
- **Library:** spring-ai-solr-store isn't published, so it has to be installed locally.
- **Data processing:** with the default local models nothing leaves the server. With Voyage, catalog text and
  search queries go to Voyage AI (US), which needs a data processing agreement and a privacy review.
- **Answer model:** local Ollama is for development. Production needs a hosted model.
- **Numbers:** the plain result list doesn't understand constraints like "under 700 W"
  (see [Hybrid search: deferred](docs/decisions.md#hybrid-search-deferred-2026-10-05)).

## License

Copyright 2026 Dawid Michałowicz. Licensed under the [Apache License 2.0](LICENSE), test data in `data/`
included.

