package io.github.emenowicz.semanticsearch;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.hybris.platform.commerceservices.i18n.CommerceCommonI18NService;
import de.hybris.platform.commerceservices.search.solrfacetsearch.strategies.SolrFacetSearchConfigSelectionStrategy;
import de.hybris.platform.commerceservices.search.solrfacetsearch.strategies.exceptions.NoValidSolrConfigException;
import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.solrfacetsearch.config.FacetSearchConfig;
import de.hybris.platform.solrfacetsearch.config.FacetSearchConfigService;
import de.hybris.platform.solrfacetsearch.config.IndexedProperty;
import de.hybris.platform.solrfacetsearch.config.IndexedType;
import de.hybris.platform.solrfacetsearch.config.exceptions.FacetConfigServiceException;
import de.hybris.platform.solrfacetsearch.provider.FieldNameProvider;
import de.hybris.platform.solrfacetsearch.solr.Index;
import de.hybris.platform.solrfacetsearch.solr.SolrIndexService;
import de.hybris.platform.solrfacetsearch.solr.SolrSearchProvider;
import de.hybris.platform.solrfacetsearch.solr.SolrSearchProviderFactory;
import de.hybris.platform.solrfacetsearch.solr.exceptions.SolrServiceException;

import io.github.emenowicz.springai.vectorstore.solr.SolrVectorStore;

import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * kNN over the current site's product index, in the session language's vector field.
 *
 * <p>
 * The index is resolved per call, because the active index can flip in two-phase indexing. The Solr client is the
 * platform's pooled {@code CachedSolrClient} (the same instance per index), so it is not closed here.
 */
public class SemanticSearchService {

	private SolrFacetSearchConfigSelectionStrategy solrFacetSearchConfigSelectionStrategy;

	private FacetSearchConfigService facetSearchConfigService;

	private SolrIndexService solrIndexService;

	private SolrSearchProviderFactory solrSearchProviderFactory;

	private FieldNameProvider fieldNameProvider;

	private CommerceCommonI18NService commerceCommonI18NService;

	private EmbeddingModel embeddingModel;

	// building a store loads Spring AI's default tokenizer (~30 ms, ~40 MB), so keep one per client, index and language.
	// ponytail: a store for a client the pool replaced stays in the map; a few dozen entries at most
	private final Map<List<Object>, SolrVectorStore> stores = new ConcurrentHashMap<>();

	/** description is null when the index has no description for the language. */
	public record Hit(String code, String name, String description, double score) {
	}

	/**
	 * @throws SemanticSearchUnavailableException no index, Solr or the embedding API down (503 in OCC)
	 * @throws EmbeddingBudgetExceededException too many embedding calls this minute (429 in OCC)
	 */
	public List<Hit> search(String query, int topK) {
		try {
			FacetSearchConfig config = this.facetSearchConfigService
				.getConfiguration(this.solrFacetSearchConfigSelectionStrategy.getCurrentSolrFacetSearchConfig().getName());
			// one indexed type per config, as the platform's own SearchSolrQueryPopulator assumes
			IndexedType type = config.getIndexConfig().getIndexedTypes().values().iterator().next();
			String qualifier = this.solrIndexService.getActiveIndex(config.getName(), type.getIdentifier())
				.getQualifier();
			SolrSearchProvider provider = this.solrSearchProviderFactory.getSearchProvider(config, type);
			Index index = provider.resolveIndex(config, type, qualifier);
			String language = indexLanguage(config);
			Map<String, IndexedProperty> properties = type.getIndexedProperties();
			String descriptionField = properties.containsKey("description")
					? field(properties.get("description"), language) : null;

			SolrClient client = provider.getClient(index);
			SolrVectorStore store = this.stores.computeIfAbsent(List.of(client, index.getName(), language),
					key -> SolrVectorStore.builder(client, index.getName(), this.embeddingModel)
						.idField(field(properties.get("code"), null))
						.contentField(field(properties.get("name"), language))
						.vectorField(field(properties.get("embedding"), language))
						// ponytail: "" returns every stored field as metadata; fine at topK 50, narrow fl if payloads grow
						.metadataPrefix("")
						.build());
			return store.similaritySearch(SearchRequest.builder().query(query).topK(topK).build())
				.stream()
				.map(document -> new Hit(document.getId(), document.getText(),
						descriptionField == null ? null : (String) document.getMetadata().get(descriptionField),
						document.getScore()))
				.toList();
		}
		catch (NoValidSolrConfigException | FacetConfigServiceException | SolrServiceException e) {
			throw new SemanticSearchUnavailableException("Semantic search index is unavailable", e);
		}
		catch (EmbeddingBudgetExceededException e) {
			throw e;
		}
		catch (RuntimeException e) {
			// the Solr query or the embedding model failed: Voyage throws IllegalStateException, the Ollama
			// client Spring's RestClientException; either way the shop falls back to keyword search
			throw new SemanticSearchUnavailableException("Semantic search is unavailable", e);
		}
	}

	// a store language that isn't indexed has no vector field: search the store default instead of failing
	private String indexLanguage(FacetSearchConfig config) {
		Collection<LanguageModel> indexed = config.getIndexConfig().getLanguages();
		LanguageModel session = this.commerceCommonI18NService.getCurrentLanguage();
		if (indexed.contains(session)) {
			return session.getIsocode();
		}
		LanguageModel storeDefault = this.commerceCommonI18NService.getDefaultLanguage();
		return (indexed.contains(storeDefault) ? storeDefault : indexed.iterator().next()).getIsocode();
	}

	// the platform's own naming (e.g. embedding_de_knn1024, name_text_de, code_string), never hand-built
	private String field(IndexedProperty property, String language) {
		return this.fieldNameProvider.getFieldName(property, language, FieldNameProvider.FieldType.INDEX);
	}

	public void setSolrFacetSearchConfigSelectionStrategy(
			SolrFacetSearchConfigSelectionStrategy solrFacetSearchConfigSelectionStrategy) {
		this.solrFacetSearchConfigSelectionStrategy = solrFacetSearchConfigSelectionStrategy;
	}

	public void setFacetSearchConfigService(FacetSearchConfigService facetSearchConfigService) {
		this.facetSearchConfigService = facetSearchConfigService;
	}

	public void setSolrIndexService(SolrIndexService solrIndexService) {
		this.solrIndexService = solrIndexService;
	}

	public void setSolrSearchProviderFactory(SolrSearchProviderFactory solrSearchProviderFactory) {
		this.solrSearchProviderFactory = solrSearchProviderFactory;
	}

	public void setFieldNameProvider(FieldNameProvider fieldNameProvider) {
		this.fieldNameProvider = fieldNameProvider;
	}

	public void setCommerceCommonI18NService(CommerceCommonI18NService commerceCommonI18NService) {
		this.commerceCommonI18NService = commerceCommonI18NService;
	}

	public void setEmbeddingModel(EmbeddingModel embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

}
