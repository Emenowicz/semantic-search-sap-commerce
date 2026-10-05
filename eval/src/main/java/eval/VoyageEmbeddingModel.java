package eval;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Voyage AI embeddings for one (model, dimensions, input_type), with a disk cache.
 *
 * <p>
 * Voyage embeds documents and queries differently ({@code input_type}), but a Spring AI
 * store has one model for both, so the eval builds two stores over the same collection.
 */
class VoyageEmbeddingModel implements EmbeddingModel {

	private static final URI API = URI.create("https://api.voyageai.com/v1/embeddings");

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final Path CACHE = Path.of("target", "cache");

	// ponytail: free tier without a payment method allows 3 requests and 10K tokens a minute,
	// so requests are spaced 21s apart and batches kept around 2.5K tokens. Drop both with billing on.
	private static final Duration SPACING = Duration.ofSeconds(21);

	private static final int BATCH_CHARS = 8_000;

	private static long nextRequestAt;

	private static long tokensUsed;

	private final String apiKey;

	private final String model;

	private final int dimensions;

	private final String inputType;

	VoyageEmbeddingModel(String apiKey, String model, int dimensions, String inputType) {
		this.apiKey = apiKey;
		this.model = model;
		this.dimensions = dimensions;
		this.inputType = inputType;
	}

	static long tokensUsed() {
		return tokensUsed;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<String> texts = request.getInstructions();
		warm(texts);
		List<Embedding> embeddings = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			embeddings.add(new Embedding(read(texts.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
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

	/** Embeds every uncached text in as few requests as the rate limit allows. */
	void warm(List<String> texts) {
		List<String> missing = new ArrayList<>(new LinkedHashSet<>(texts));
		missing.removeIf(text -> Files.exists(cacheFile(text)));
		List<String> batch = new ArrayList<>();
		int chars = 0;
		for (String text : missing) {
			if (!batch.isEmpty() && chars + text.length() > BATCH_CHARS) {
				fetch(batch);
				batch = new ArrayList<>();
				chars = 0;
			}
			batch.add(text);
			chars += text.length();
		}
		if (!batch.isEmpty()) {
			fetch(batch);
		}
	}

	private void fetch(List<String> texts) {
		try {
			String body = JSON.writeValueAsString(Map.of("input", texts, "model", this.model, "input_type",
					this.inputType, "output_dimension", this.dimensions));
			JsonNode response = post(body, this.apiKey);
			tokensUsed += response.path("usage").path("total_tokens").asLong();
			for (JsonNode item : response.path("data")) {
				Path file = cacheFile(texts.get(item.path("index").asInt()));
				Files.createDirectories(file.getParent());
				JSON.writeValue(file.toFile(), JSON.treeToValue(item.path("embedding"), float[].class));
			}
		}
		catch (IOException e) {
			throw new IllegalStateException("Voyage request failed", e);
		}
	}

	private static synchronized JsonNode post(String body, String apiKey) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(API)
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		for (int attempt = 1;; attempt++) {
			sleepUntil(nextRequestAt);
			HttpResponse<String> response;
			try {
				response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
			nextRequestAt = System.currentTimeMillis() + SPACING.toMillis();
			if (response.statusCode() == 200) {
				return JSON.readTree(response.body());
			}
			if (response.statusCode() != 429 || attempt == 5) {
				throw new IOException("Voyage HTTP " + response.statusCode() + ": " + response.body());
			}
			System.out.println("  rate limited, retrying in 60s");
			nextRequestAt = System.currentTimeMillis() + 60_000;
		}
	}

	private static void sleepUntil(long millis) {
		long wait = millis - System.currentTimeMillis();
		if (wait > 0) {
			try {
				Thread.sleep(wait);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private float[] read(String text) {
		try {
			return JSON.readValue(cacheFile(text).toFile(), float[].class);
		}
		catch (IOException e) {
			throw new IllegalStateException("Missing cached embedding", e);
		}
	}

	private Path cacheFile(String text) {
		return CACHE.resolve(this.model).resolve(this.dimensions + "-" + this.inputType).resolve(sha256(text) + ".json");
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
