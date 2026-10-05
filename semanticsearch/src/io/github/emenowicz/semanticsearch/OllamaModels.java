package io.github.emenowicz.semanticsearch;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * Models on a local Ollama server, built by Spring XML (factory-method). Another provider is another factory;
 * the services only see Spring AI's {@link ChatModel} and {@link EmbeddingModel}.
 */
public final class OllamaModels {

	// keep the model loaded, or the first request after a few idle minutes pays the load time
	private static final String KEEP_ALIVE = "30m";

	private OllamaModels() {
	}

	/** The answer model: JSON constrained to SemanticAnswerService's schema, one attempt, then the product list. */
	public static ChatModel chat(String baseUrl, String model, long timeoutMs, int contextTokens, int maxTokens,
			boolean disableThinking) {
		OllamaChatOptions.Builder options = OllamaChatOptions.builder()
			.model(model)
			.temperature(0.0)
			// Ollama's default context is small and truncates silently
			.numCtx(contextTokens)
			// caps the paid output; a cut-off answer is invalid JSON and falls back to the list
			.numPredict(maxTokens)
			.keepAlive(KEEP_ALIVE)
			// constrains the output to the Draft schema server-side; SemanticAnswerService still validates it
			.format(SemanticAnswerService.CONVERTER.getJsonSchemaMap());
		if (disableThinking) {
			// a <think> block before the JSON would break parsing, and hosted models bill thinking as output
			options.disableThinking();
		}
		return OllamaChatModel.builder()
			.ollamaApi(api(baseUrl, timeoutMs))
			.defaultOptions(options.build())
			// Spring AI's default retries 10 times with backoff up to 3 minutes; a shopper's request gets one
			// attempt and then the product list
			.retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
			.build();
	}

	/**
	 * Embeddings for both sides (bge-m3 has no query/document distinction). No retries in OllamaEmbeddingModel; a
	 * failure becomes a 503 in SemanticSearchService. Unlike VoyageEmbeddingModel, no query cache or call budget:
	 * the model is local and free.
	 */
	public static EmbeddingModel embedding(String baseUrl, String model, long timeoutMs) {
		return OllamaEmbeddingModel.builder()
			.ollamaApi(api(baseUrl, timeoutMs))
			.defaultOptions(OllamaEmbeddingOptions.builder().model(model).keepAlive(KEEP_ALIVE).build())
			.build();
	}

	// the default client has no read timeout, and a hybris request thread waits on these calls
	private static OllamaApi api(String baseUrl, long timeoutMs) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
		return OllamaApi.builder()
			.baseUrl(baseUrl)
			.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
			.build();
	}

}
