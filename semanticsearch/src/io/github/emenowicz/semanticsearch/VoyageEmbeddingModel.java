package io.github.emenowicz.semanticsearch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Voyage AI embeddings for one (model, dimensions, input_type). Spring AI has no Voyage module.
 *
 * <p>
 * Voyage 4 models share one embedding space, so queries embedded with voyage-4 match products
 * indexed with voyage-4-large (eval in ../eval).
 */
public class VoyageEmbeddingModel implements EmbeddingModel {

	private static final URI API = URI.create("https://api.voyageai.com/v1/embeddings");

	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpClient http;

	private final String apiKey;

	private final String model;

	private final int dimensions;

	private final String inputType;

	private final Duration timeout;

	private final int batchSize;

	private final int maxAttempts;

	private long minIntervalMs;

	private long nextRequestAt;

	// Caffeine ships with the platform (ext/core/lib); size 0 keeps nothing, as on the document side
	private Cache<String, float[]> cache = Caffeine.newBuilder().maximumSize(0).build();

	private int maxRequestsPerMinute;

	private long budgetWindowStart;

	private int budgetUsed;

	/**
	 * @param batchSize texts per request (Voyage allows 1000)
	 * @param maxAttempts attempts on HTTP 429; 1 for the query side, where waiting is worse than failing
	 */
	public VoyageEmbeddingModel(String apiKey, String model, int dimensions, String inputType, long timeoutMs,
			int batchSize, int maxAttempts) {
		this.apiKey = apiKey;
		this.model = model;
		this.dimensions = dimensions;
		this.inputType = inputType;
		this.timeout = Duration.ofMillis(timeoutMs);
		this.batchSize = batchSize;
		this.maxAttempts = maxAttempts;
		this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		if (this.apiKey == null || this.apiKey.isBlank()) {
			throw new IllegalStateException("semanticsearch.voyage.api.key is not set");
		}
		List<String> texts = request.getInstructions();
		// cached texts come from memory; the rest are fetched in batches and cached
		Map<String, float[]> vectors = this.cache.getAll(texts, this::fetch);
		List<Embedding> embeddings = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			embeddings.add(new Embedding(vectors.get(texts.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	private Map<String, float[]> fetch(Set<? extends String> missing) {
		List<String> texts = new ArrayList<>(missing);
		Map<String, float[]> vectors = new HashMap<>();
		for (int from = 0; from < texts.size(); from += this.batchSize) {
			List<String> batch = texts.subList(from, Math.min(from + this.batchSize, texts.size()));
			spendBudget();
			for (JsonNode item : post(batch).path("data")) {
				vectors.put(batch.get(item.path("index").asInt()), toFloats(item.path("embedding")));
			}
		}
		return vectors;
	}

	/**
	 * Keeps the vectors of the last {@code size} texts: a repeated query costs no API call. 1000 entries of
	 * 1024 floats are about 4 MB. 0 (the default) turns it off, as on the document side.
	 */
	public void setCacheSize(int size) {
		this.cache = Caffeine.newBuilder().maximumSize(Math.max(size, 0)).build();
	}

	/**
	 * Caps API calls per minute (cache hits are free), so a public endpoint can't run up the bill. 0 = no cap.
	 * ponytail: one budget per node, not per client; per-client limits belong in front of the app (WAF, gateway)
	 */
	public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
		this.maxRequestsPerMinute = maxRequestsPerMinute;
	}

	private synchronized void spendBudget() {
		if (this.maxRequestsPerMinute <= 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - this.budgetWindowStart >= 60_000) {
			this.budgetWindowStart = now;
			this.budgetUsed = 0;
		}
		if (++this.budgetUsed > this.maxRequestsPerMinute) {
			throw new EmbeddingBudgetExceededException(
					"More than " + this.maxRequestsPerMinute + " embedding requests a minute, try again shortly");
		}
	}

	/**
	 * Minimum time between requests. ponytail: only for Voyage's free tier (3 requests a minute), where the
	 * indexer's own retries can't wait long enough; 0 (no wait) with a payment method on the account.
	 */
	public void setMinIntervalMs(long minIntervalMs) {
		this.minIntervalMs = minIntervalMs;
	}

	private synchronized void awaitTurn() throws InterruptedException {
		long wait = this.nextRequestAt - System.currentTimeMillis();
		if (wait > 0) {
			Thread.sleep(wait);
		}
		this.nextRequestAt = System.currentTimeMillis() + this.minIntervalMs;
	}

	private JsonNode post(List<String> texts) {
		try {
			String body = JSON.writeValueAsString(Map.of("input", texts, "model", this.model, "input_type",
					this.inputType, "output_dimension", this.dimensions));
			HttpRequest httpRequest = HttpRequest.newBuilder(API)
				.timeout(this.timeout)
				.header("Authorization", "Bearer " + this.apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			for (int attempt = 1;; attempt++) {
				awaitTurn();
				HttpResponse<String> response = this.http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 200) {
					return JSON.readTree(response.body());
				}
				if (response.statusCode() != 429 || attempt >= this.maxAttempts) {
					throw new IllegalStateException("Voyage HTTP " + response.statusCode() + ": " + response.body());
				}
				// ponytail: fixed 21s when Retry-After is missing, enough for the free tier's 3 requests a minute
				long waitSeconds = response.headers().firstValueAsLong("Retry-After").orElse(21);
				Thread.sleep(waitSeconds * 1000);
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Voyage request failed", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Voyage request interrupted", e);
		}
	}

	private static float[] toFloats(JsonNode array) {
		try {
			return JSON.treeToValue(array, float[].class);
		}
		catch (IOException e) {
			throw new IllegalStateException("Unexpected Voyage response", e);
		}
	}

	@Override
	public float[] embed(Document document) {
		return embed(document.getText());
	}

	// the configured value; the interface default would spend an API call to find it
	@Override
	public int dimensions() {
		return this.dimensions;
	}

}
