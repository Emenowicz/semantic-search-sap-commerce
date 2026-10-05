package io.github.emenowicz.semanticsearch;

import java.util.List;
import java.util.Map;

import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.solrfacetsearch.config.IndexedProperty;
import de.hybris.platform.solrfacetsearch.config.exceptions.FieldValueProviderException;
import de.hybris.platform.solrfacetsearch.indexer.IndexerBatchContext;
import de.hybris.platform.solrfacetsearch.indexer.spi.InputDocument;
import de.hybris.platform.solrfacetsearch.provider.impl.AbstractValueResolver;

import org.springframework.ai.model.EmbeddingUtils;

/**
 * Writes the stored product vector per index language to a localized {@code DenseVectorField}, e.g.
 * {@code embedding_de_knn1024}.
 *
 * <p>
 * It only reads: productEmbeddingJob computes the vectors, so indexing makes no embedding calls and keeps working
 * when the provider is down. A product without a current vector (new, or its text changed since the job ran) gets
 * no vector field and is left out of semantic search until the next job and index run. The batch's vectors are
 * loaded in one query and kept in the batch context.
 */
public class ProductEmbeddingValueResolver extends AbstractValueResolver<ProductModel, Object, Object> {

	private static final String BATCH_VECTORS = ProductEmbeddingValueResolver.class.getName();

	private ProductEmbeddingService productEmbeddingService;

	@Override
	protected void addFieldValues(InputDocument document, IndexerBatchContext batchContext,
			IndexedProperty indexedProperty, ProductModel product, ValueResolverContext<Object, Object> resolverContext)
			throws FieldValueProviderException {
		LanguageModel language = resolverContext.getQualifier().getValueForType(LanguageModel.class);
		float[] vector = batchVectors(batchContext).get(ProductEmbeddingService.key(product, language));
		if (vector != null) {
			filterAndAddFieldValues(document, batchContext, indexedProperty, EmbeddingUtils.toList(vector),
					resolverContext.getFieldQualifier());
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, float[]> batchVectors(IndexerBatchContext batchContext) {
		return (Map<String, float[]>) batchContext.getAttributes()
			.computeIfAbsent(BATCH_VECTORS, k -> {
				List<ProductModel> products = batchContext.getItems()
					.stream()
					.filter(ProductModel.class::isInstance)
					.map(ProductModel.class::cast)
					.toList();
				return this.productEmbeddingService.currentVectors(products,
						batchContext.getFacetSearchConfig().getIndexConfig().getLanguages());
			});
	}

	public void setProductEmbeddingService(ProductEmbeddingService productEmbeddingService) {
		this.productEmbeddingService = productEmbeddingService;
	}

}
