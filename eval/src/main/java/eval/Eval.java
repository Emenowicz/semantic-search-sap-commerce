package eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.testcontainers.solr.SolrContainer;

import io.github.emenowicz.springai.vectorstore.solr.SolrVectorStore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * Which Voyage model, how many dimensions and what text to embed, measured on the 40 synthetic
 * hardware products in data/ (EN/DE/FR/IT) through spring-ai-solr-store on Solr 9.10.
 *
 * <p>
 * Run from {@code eval/}: {@code mvn -q compile exec:java} (all configs) or
 * {@code mvn -q compile exec:java -Dexec.args=smoke} (first config, prints top 3).
 */
public class Eval {

	private static final Path PRODUCTS = Path.of("../data/parts-catalog.impex");

	private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it");

	// 4 language variants per product, so 40 hits still give at least 10 distinct products
	private static final int TOP_K = 40;

	record Product(String code, Map<String, String> text) {
	}

	record Query(String id, String lang, String kind, String text, Set<String> expected) {
	}

	record Config(String name, String indexModel, String queryModel, int dimensions, boolean englishOnly) {
	}

	record Result(Query query, List<String> codes, int rank) {

		int hit1() {
			return this.rank == 1 ? 1 : 0;
		}

		int hit5() {
			return this.rank > 0 && this.rank <= 5 ? 1 : 0;
		}

		// share of the expected set in the top 10 products: for "all sealed 20 mm bearings", one hit isn't the answer
		double recall10() {
			return this.codes.subList(0, Math.min(10, this.codes.size())).stream()
				.filter(this.query.expected()::contains).count() / (double) this.query.expected().size();
		}

		double reciprocalRank() {
			return this.rank > 0 && this.rank <= 10 ? 1.0 / this.rank : 0;
		}

	}

	// exits explicitly: SolrJ's Jetty client leaves non-daemon threads, which would hang exec:java on an exception
	public static void main(String[] args) {
		try {
			evaluate(args);
			System.exit(0);
		}
		catch (Throwable e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void evaluate(String[] args) throws Exception {
		List<Product> products = products();
		List<Query> queries = queries();
		List<Config> configs = configs();
		// no argument: the local models only; "all" adds the Voyage configs (key in ../.env, free tier is slow);
		// "only:<part of a name>" picks from all of them
		if (args.length == 0 || args[0].equals("smoke")) {
			configs = configs.stream().filter(c -> c.indexModel().startsWith("ollama:")).toList();
		}
		boolean smoke = args.length > 0 && args[0].equals("smoke");
		if (smoke) {
			configs = configs.subList(0, 1);
		}
		// "only:<part of a name>[,<part>...]" runs the matching configs and only prints, unless "write" follows
		if (args.length > 0 && args[0].startsWith("only:")) {
			List<String> parts = List.of(args[0].substring("only:".length()).split(","));
			configs = configs.stream().filter(c -> parts.stream().anyMatch(c.name()::contains)).toList();
			smoke = !(args.length > 1 && args[1].equals("write"));
		}

		String apiKey = configs.stream().allMatch(c -> c.indexModel().startsWith("ollama:")) ? null : apiKey();
		List<String> kinds = queries.stream().map(Query::kind).distinct().toList();

		List<String> results = new ArrayList<>();
		results.add("config\tquery\tlang\tkind\texpected\ttop1\trank\thit1\thit5\trr\trecall10\tindex_model\tquery_model\tdims");
		List<String> summary = new ArrayList<>();
		summary.add("config\tqueries\thit1\thit5\tmrr\trecall10\t" + String.join("\t", kinds.stream().map(k -> "mrr_" + k).toList())
				+ "\tnew_tokens");
		for (Config config : configs) {
			System.out.println("== " + config.name());
			long tokensBefore = VoyageEmbeddingModel.tokensUsed();
			List<Result> configResults = run(apiKey, config, products, queries);
			long newTokens = VoyageEmbeddingModel.tokensUsed() - tokensBefore;
			for (Result r : configResults) {
				results.add(String.join("\t", config.name(), r.query().id(), r.query().lang(), r.query().kind(),
						String.join("|", r.query().expected()), r.codes().isEmpty() ? "" : r.codes().get(0),
						String.valueOf(r.rank()), String.valueOf(r.hit1()), String.valueOf(r.hit5()),
						fmt(r.reciprocalRank()), fmt(r.recall10()), config.indexModel(), config.queryModel(),
						String.valueOf(config.dimensions())));
				if (smoke) {
					System.out.println("  " + r.query().id() + " " + r.query().text() + " -> "
							+ r.codes().subList(0, Math.min(3, r.codes().size())) + " rank " + r.rank());
				}
			}
			List<String> cells = new ArrayList<>(List.of(config.name(), String.valueOf(configResults.size()),
					fmt(mean(configResults, Result::hit1)), fmt(mean(configResults, Result::hit5)),
					fmt(mean(configResults, Result::reciprocalRank)), fmt(mean(configResults, Result::recall10))));
			kinds.forEach(kind -> cells.add(fmt(mean(filter(configResults, kind::equals), Result::reciprocalRank))));
			cells.add(String.valueOf(newTokens));
			String row = String.join("\t", cells);
			summary.add(row);
			System.out.println("  " + row);
		}
		if (!smoke) {
			Files.write(Path.of("results", "results.tsv"), results);
			Files.write(Path.of("results", "summary.tsv"), summary);
			System.out.println("wrote results/results.tsv and results/summary.tsv");
		}
	}

	private static List<Result> run(String apiKey, Config config, List<Product> products, List<Query> queries)
			throws Exception {
		EmbeddingModel documentModel = model(apiKey, config.indexModel(), config.dimensions(), "document");
		EmbeddingModel queryModel = model(apiKey, config.queryModel(), config.dimensions(), "query");
		List<Document> documents = new ArrayList<>();
		for (Product product : products) {
			for (String lang : config.englishOnly() ? List.of("en") : LANGUAGES) {
				documents.add(new Document(product.code() + "-" + lang, product.text().get(lang),
						Map.of("code", product.code(), "lang", lang)));
			}
		}
		// Voyage: one batched, cached call per side instead of one per text (free tier: 3 requests a minute)
		if (documentModel instanceof VoyageEmbeddingModel voyage) {
			voyage.warm(documents.stream().map(Document::getText).toList());
		}
		if (queryModel instanceof VoyageEmbeddingModel voyage) {
			voyage.warm(queries.stream().map(Query::text).toList());
		}

		// ponytail: fresh Solr per config (~10s) because the vector field's dimension is fixed in the schema
		try (SolrContainer solr = new SolrContainer("solr:9.10").withCollection("eval")) {
			solr.start();
			try (SolrClient client = new Http2SolrClient.Builder(
					"http://" + solr.getHost() + ":" + solr.getSolrPort() + "/solr")
				.build()) {
				SolrVectorStore indexer = SolrVectorStore.builder(client, "eval", documentModel)
					.initializeSchema(true)
					.build();
				indexer.afterPropertiesSet();
				indexer.add(documents);
				// same collection, query-side embeddings
				SolrVectorStore searcher = SolrVectorStore.builder(client, "eval", queryModel).build();

				List<Result> results = new ArrayList<>();
				for (Query query : queries) {
					List<Document> hits = searcher.similaritySearch(
							SearchRequest.builder().query(query.text()).topK(TOP_K).similarityThresholdAll().build());
					// a product counts once, at the rank of its best language variant
					List<String> codes = hits.stream().map(hit -> (String) hit.getMetadata().get("code")).distinct().toList();
					int rank = 0;
					for (int i = 0; i < codes.size() && rank == 0; i++) {
						if (query.expected().contains(codes.get(i))) {
							rank = i + 1;
						}
					}
					results.add(new Result(query, codes, rank));
				}
				return results;
			}
		}
	}

	// "ollama:<model>" is a local Ollama model (no query/document distinction); anything else is a Voyage model
	private static EmbeddingModel model(String apiKey, String name, int dimensions, String inputType) {
		if (name.startsWith("ollama:")) {
			// JDK HTTP client: with SolrJ's Jetty 10 on the classpath, Spring's RestClient would pick Jetty and expect 12
			OllamaApi api = OllamaApi.builder()
				.restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
				.build();
			return OllamaEmbeddingModel.builder()
				.ollamaApi(api)
				.defaultOptions(OllamaEmbeddingOptions.builder().model(name.substring("ollama:".length())).build())
				.build();
		}
		return new VoyageEmbeddingModel(apiKey, name, dimensions, inputType);
	}

	private static List<Config> configs() {
		List<Config> configs = new ArrayList<>();
		for (String model : List.of("voyage-4-lite", "voyage-4", "voyage-4-large")) {
			for (int dimensions : List.of(256, 1024, 2048)) {
				configs.add(new Config(model + "/" + dimensions, model, model, dimensions, false));
			}
		}
		configs.add(new Config("voyage-4/1024/english-only", "voyage-4", "voyage-4", 1024, true));
		// Voyage 4 models share one embedding space: index once with large, query with a cheaper model
		configs.add(new Config("index-large/query-lite/1024", "voyage-4-large", "voyage-4-lite", 1024, false));
		configs.add(new Config("index-large/query-4/1024", "voyage-4-large", "voyage-4", 1024, false));
		// local and free: the default provider of the extension
		configs.add(new Config("bge-m3/1024 (ollama)", "ollama:bge-m3", "ollama:bge-m3", 1024, false));
		return configs;
	}

	private static List<Product> products() throws IOException {
		List<Product> products = new ArrayList<>();
		boolean inProducts = false;
		for (String line : Files.readAllLines(PRODUCTS)) {
			if (line.startsWith("INSERT_UPDATE ")) {
				inProducts = line.startsWith("INSERT_UPDATE Product;");
				continue;
			}
			if (!inProducts || !line.startsWith("; ")) {
				continue;
			}
			// ; code ; catalogVersion ; name[en,de,fr,it] ; description[en,de,fr,it] ; supercategories
			List<String> fields = impexFields(line);
			Map<String, String> text = new LinkedHashMap<>();
			for (int i = 0; i < LANGUAGES.size(); i++) {
				text.put(LANGUAGES.get(i), fields.get(3 + i) + ". " + fields.get(7 + i));
			}
			products.add(new Product(fields.get(1), text));
		}
		return products;
	}

	// ponytail: enough impex for this file: ';' separates fields except inside "...", no "" escapes
	private static List<String> impexFields(String line) {
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (char c : line.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
			}
			else if (c == ';' && !quoted) {
				fields.add(field.toString().strip());
				field.setLength(0);
			}
			else {
				field.append(c);
			}
		}
		fields.add(field.toString().strip());
		return fields;
	}

	private static List<Query> queries() throws IOException {
		List<Query> queries = new ArrayList<>();
		List<String> lines = Files.readAllLines(Path.of("queries.tsv"));
		for (String line : lines.subList(1, lines.size())) {
			String[] f = line.split("\t");
			queries.add(new Query(f[0], f[1], f[2], f[3], Set.of(f[4].split("\\|"))));
		}
		return queries;
	}

	private static String apiKey() throws IOException {
		String key = System.getenv("VOYAGE_API_KEY");
		Path env = Path.of("../.env");
		if ((key == null || key.isBlank()) && Files.exists(env)) {
			for (String line : Files.readAllLines(env)) {
				if (line.startsWith("VOYAGE_API_KEY=")) {
					key = line.substring("VOYAGE_API_KEY=".length()).strip();
				}
			}
		}
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("Set VOYAGE_API_KEY in the environment or in ../.env");
		}
		return key;
	}

	private static List<Result> filter(List<Result> results, Predicate<String> kind) {
		return results.stream().filter(r -> kind.test(r.query().kind())).toList();
	}

	private static double mean(List<Result> results, ToDoubleFunction<Result> metric) {
		return results.stream().mapToDouble(metric).average().orElse(Double.NaN);
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

}
