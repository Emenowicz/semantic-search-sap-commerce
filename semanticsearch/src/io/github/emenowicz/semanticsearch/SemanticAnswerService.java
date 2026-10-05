package io.github.emenowicz.semanticsearch;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.hybris.platform.servicelayer.i18n.CommonI18NService;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Answers a shopper's question from the products semantic search found, and only from them.
 *
 * <p>
 * The model suggests, the code decides: the model must return JSON citing product codes, and every cited code
 * has to be one of the retrieved products. Anything else falls back to the plain product list. The model is any
 * Spring AI {@link ChatModel} (Ollama locally; another provider is a different bean).
 *
 * <p>
 * Model calls are the running cost, so they are spared: codes and short queries get the list only (SKIPPED), a
 * repeated question with the same results reuses the stored model output, and a daily cap bounds the spend.
 */
public class SemanticAnswerService {

	/** What the model must return. Its JSON schema also constrains the model server-side (Ollama format). */
	public record Draft(String answer, List<String> productCodes) {
	}

	/** SKIPPED: no answer by design (codes, short queries); FALLBACK: an answer was wanted but failed or was rejected. */
	public enum Status {
		ANSWERED, FALLBACK, SKIPPED
	}

	public record Result(Status status, String answer, List<String> citedCodes, String reason,
			List<SemanticSearchService.Hit> products) {
	}

	static final BeanOutputConverter<Draft> CONVERTER = new BeanOutputConverter<>(Draft.class);

	// first and unchanged between requests, so providers that cache prompt prefixes can reuse it
	private static final String SYSTEM_PROMPT = """
			You are a product advisor in a hardware shop. Answer the customer's question using ONLY the products \
			listed in the user message. Never mention a product, brand, price or fact that is not in that list.
			If no listed product fits, say so briefly and cite no products.
			Write the answer in the language given in the user message, in 1 to 3 sentences.
			Put the codes of the products your answer recommends in productCodes, exactly as listed.
			""" + CONVERTER.getFormat();

	private SemanticSearchService semanticSearchService;

	private ChatModel chatModel;

	private CommonI18NService commonI18NService;

	// a word of 4+ letters; codes like 6204-2RS, M8x30 or SPZ 1250 have none
	private static final Pattern WORD = Pattern.compile("\\p{L}{4,}");

	private int topK;

	private int minWords;

	// ponytail: per node, like the query cache; a shared cache (Redis) only if nodes miss each other's questions often
	private Cache<List<Object>, String> cache = Caffeine.newBuilder().maximumSize(0).build();

	private int maxAnswersPerDay;

	private long budgetDay;

	private int budgetUsed;

	/** Answers in the session language (the {@code lang} parameter in OCC). */
	public Result answer(String query) {
		String language = this.commonI18NService.getCurrentLanguage().getIsocode();
		List<SemanticSearchService.Hit> products = this.semanticSearchService.search(query, this.topK);
		if (products.isEmpty()) {
			return fallback("no products found", products);
		}
		if (!worthAnswering(query, this.minWords)) {
			return new Result(Status.SKIPPED, null, List.of(), "codes and short queries get the list only", products);
		}
		// same question, language and results: same answer (temperature 0), so the stored output is reused
		List<Object> key = List.of(language, query.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "),
				products.stream().map(SemanticSearchService.Hit::code).toList());
		String text = this.cache.getIfPresent(key);
		if (text == null) {
			if (!spendBudget()) {
				return fallback("daily answer budget used up", products);
			}
			try {
				text = this.chatModel.call(new SystemMessage(SYSTEM_PROMPT),
						new UserMessage(userPrompt(query, language, products)));
			}
			catch (RuntimeException e) {
				// the search worked, so the shopper still gets the list; not cached, the next request retries
				return fallback("answer model unavailable: " + e.getClass().getSimpleName(), products);
			}
			// a rejected output is cached too: asking again would give the same one
			this.cache.put(key, text);
		}
		return validate(text, products);
	}

	/** Package-private for the unit test: whether a query is worth a model call. */
	static boolean worthAnswering(String query, int minWords) {
		return WORD.matcher(query).results().count() >= minWords;
	}

	/**
	 * ponytail: per node and in memory, so the real cap is nodes x maxAnswersPerDay and a restart resets the day;
	 * a shared counter in the database if that ever matters. A safety ceiling on spend, not billing.
	 */
	synchronized boolean spendBudget() {
		if (this.maxAnswersPerDay <= 0) {
			return true;
		}
		long today = System.currentTimeMillis() / Duration.ofDays(1).toMillis();
		if (today != this.budgetDay) {
			this.budgetDay = today;
			this.budgetUsed = 0;
		}
		return ++this.budgetUsed <= this.maxAnswersPerDay;
	}

	/** Package-private for the unit test: the whole grounding guarantee lives here. */
	static Result validate(String modelOutput, List<SemanticSearchService.Hit> products) {
		Draft draft;
		try {
			draft = CONVERTER.convert(modelOutput);
		}
		catch (RuntimeException e) {
			return fallback("answer was not valid JSON", products);
		}
		if (draft == null || draft.answer() == null || draft.answer().isBlank()) {
			return fallback("empty answer", products);
		}
		List<String> cited = draft.productCodes() == null ? List.of() : draft.productCodes();
		Set<String> retrieved = products.stream().map(SemanticSearchService.Hit::code).collect(Collectors.toSet());
		List<String> unknown = cited.stream().filter(code -> !retrieved.contains(code)).toList();
		if (!unknown.isEmpty()) {
			return fallback("answer cited products outside the results: " + unknown, products);
		}
		return new Result(Status.ANSWERED, draft.answer().strip(), cited, null, products);
	}

	private static Result fallback(String reason, List<SemanticSearchService.Hit> products) {
		return new Result(Status.FALLBACK, null, List.of(), reason, products);
	}

	private static String userPrompt(String query, String language, List<SemanticSearchService.Hit> products) {
		StringBuilder prompt = new StringBuilder("Answer language: ").append(language).append("\n\nProducts:\n");
		for (SemanticSearchService.Hit product : products) {
			prompt.append("- code: ").append(product.code()).append("\n  name: ").append(product.name());
			if (product.description() != null) {
				prompt.append("\n  description: ").append(product.description());
			}
			prompt.append('\n');
		}
		// catalog text is trusted shop content; customer reviews would need separating as untrusted input
		return prompt.append("\nCustomer question: ").append(query).toString();
	}

	public void setSemanticSearchService(SemanticSearchService semanticSearchService) {
		this.semanticSearchService = semanticSearchService;
	}

	public void setChatModel(ChatModel chatModel) {
		this.chatModel = chatModel;
	}

	public void setCommonI18NService(CommonI18NService commonI18NService) {
		this.commonI18NService = commonI18NService;
	}

	public void setTopK(int topK) {
		this.topK = topK;
	}

	/** Words of 4+ letters a query needs for an answer; 0 answers everything. */
	public void setMinWords(int minWords) {
		this.minWords = minWords;
	}

	/** Model outputs kept for repeated questions, for a day (product texts may change); 0 keeps nothing. */
	public void setCacheSize(int size) {
		this.cache = Caffeine.newBuilder().maximumSize(Math.max(size, 0)).expireAfterWrite(Duration.ofDays(1)).build();
	}

	/** Model calls a day on this node; cache hits don't count, and above it the shopper gets the list. 0: no cap. */
	public void setMaxAnswersPerDay(int maxAnswersPerDay) {
		this.maxAnswersPerDay = maxAnswersPerDay;
	}

}
