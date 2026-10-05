package io.github.emenowicz.semanticsearch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import io.github.emenowicz.semanticsearch.model.ProductEmbeddingModel;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Stored product vectors ({@link ProductEmbeddingModel}), one per product and language, of {@code name. description}
 * (as in ../eval).
 *
 * <p>
 * Each row keeps a hash of the model id and the text it was computed from. {@link #refresh} embeds only texts whose
 * hash changed, and {@link #currentVectors} returns only vectors whose hash still matches, so neither a changed text
 * nor a changed model puts a stale vector in the index.
 */
public class ProductEmbeddingService {

	private static final String FIND = "SELECT {pk} FROM {ProductEmbedding} WHERE {product} IN (?products)";

	private FlexibleSearchService flexibleSearchService;

	private ModelService modelService;

	private CommonI18NService commonI18NService;

	private EmbeddingModel embeddingModel;

	// changes whenever the vector space changes (provider, model, dimensions), which makes every stored vector stale
	private String modelId;

	/** Vectors that match the products' current text and model, keyed by {@link #key}; no row or stale: absent. */
	public Map<String, float[]> currentVectors(Collection<ProductModel> products, Collection<LanguageModel> languages) {
		Map<String, ProductEmbeddingModel> stored = stored(products);
		Map<String, float[]> vectors = new HashMap<>();
		for (LanguageModel language : languages) {
			Locale locale = this.commonI18NService.getLocaleForLanguage(language);
			for (ProductModel product : products) {
				ProductEmbeddingModel row = stored.get(key(product, language));
				if (row != null && sourceHash(this.modelId, text(product, locale)).equals(row.getSourceHash())) {
					vectors.put(key(product, language), decode(row.getVector()));
				}
			}
		}
		return vectors;
	}

	/**
	 * Embeds the texts that are new or changed, in one embedding call, and saves them.
	 * @return how many texts were embedded
	 */
	public int refresh(Collection<ProductModel> products, Collection<LanguageModel> languages) {
		Map<String, ProductEmbeddingModel> stored = stored(products);
		List<ProductEmbeddingModel> rows = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		List<String> hashes = new ArrayList<>();
		for (LanguageModel language : languages) {
			Locale locale = this.commonI18NService.getLocaleForLanguage(language);
			for (ProductModel product : products) {
				String text = text(product, locale);
				String hash = sourceHash(this.modelId, text);
				ProductEmbeddingModel row = stored.get(key(product, language));
				// ponytail: a text that became empty keeps its old row; currentVectors skips it by hash
				if (text.isEmpty() || row != null && hash.equals(row.getSourceHash())) {
					continue;
				}
				if (row == null) {
					row = this.modelService.create(ProductEmbeddingModel.class);
					row.setProduct(product);
					row.setLanguage(language);
				}
				rows.add(row);
				texts.add(text);
				hashes.add(hash);
			}
		}
		if (texts.isEmpty()) {
			return 0;
		}
		// rows are only changed after the call succeeds, so a failed call leaves nothing half-written
		List<float[]> vectors = this.embeddingModel.embed(texts);
		for (int i = 0; i < rows.size(); i++) {
			rows.get(i).setSourceHash(hashes.get(i));
			rows.get(i).setVector(encode(vectors.get(i)));
		}
		this.modelService.saveAll(rows);
		return texts.size();
	}

	private Map<String, ProductEmbeddingModel> stored(Collection<ProductModel> products) {
		if (products.isEmpty()) {
			return Map.of();
		}
		FlexibleSearchQuery query = new FlexibleSearchQuery(FIND, Map.of("products", products));
		return this.flexibleSearchService.<ProductEmbeddingModel> search(query)
			.getResult()
			.stream()
			.collect(Collectors.toMap(row -> key(row.getProduct(), row.getLanguage()), row -> row));
	}

	static String text(ProductModel product, Locale locale) {
		return Stream.of(product.getName(locale), product.getDescription(locale))
			.filter(part -> part != null && !part.isBlank())
			.map(String::strip)
			.collect(Collectors.joining(". "));
	}

	static String key(ProductModel product, LanguageModel language) {
		return product.getPk() + "|" + language.getIsocode();
	}

	static String sourceHash(String modelId, String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest((modelId + "\n" + text).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("every JVM has SHA-256", e);
		}
	}

	static String encode(float[] vector) {
		ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		bytes.asFloatBuffer().put(vector);
		return Base64.getEncoder().encodeToString(bytes.array());
	}

	static float[] decode(String encoded) {
		ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.LITTLE_ENDIAN);
		float[] vector = new float[bytes.remaining() / Float.BYTES];
		bytes.asFloatBuffer().get(vector);
		return vector;
	}

	public void setFlexibleSearchService(FlexibleSearchService flexibleSearchService) {
		this.flexibleSearchService = flexibleSearchService;
	}

	public void setModelService(ModelService modelService) {
		this.modelService = modelService;
	}

	public void setCommonI18NService(CommonI18NService commonI18NService) {
		this.commonI18NService = commonI18NService;
	}

	public void setEmbeddingModel(EmbeddingModel embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	public void setModelId(String modelId) {
		this.modelId = modelId;
	}

}
