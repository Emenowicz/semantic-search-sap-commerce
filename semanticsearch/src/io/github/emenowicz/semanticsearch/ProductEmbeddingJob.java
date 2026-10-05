package io.github.emenowicz.semanticsearch;

import java.util.List;
import java.util.Map;

import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.cronjob.enums.CronJobResult;
import de.hybris.platform.cronjob.enums.CronJobStatus;
import de.hybris.platform.cronjob.model.CronJobModel;
import de.hybris.platform.servicelayer.cronjob.AbstractJobPerformable;
import de.hybris.platform.servicelayer.cronjob.PerformResult;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.solrfacetsearch.config.FacetSearchConfigService;
import de.hybris.platform.solrfacetsearch.config.IndexConfig;
import de.hybris.platform.solrfacetsearch.config.IndexedType;
import de.hybris.platform.solrfacetsearch.config.exceptions.FacetConfigServiceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embeds new and changed product texts of one Solr index (its types, catalog versions and languages) into
 * {@code ProductEmbedding} rows. Unchanged texts cost nothing, so it can run before every full index; run it first,
 * or the index misses vectors for texts changed since the last run.
 *
 * <p>
 * Stops at the first failed embedding call: the batches saved so far are kept, and the next run continues from there.
 */
public class ProductEmbeddingJob extends AbstractJobPerformable<CronJobModel> {

	private static final Logger LOG = LoggerFactory.getLogger(ProductEmbeddingJob.class);

	private FacetSearchConfigService facetSearchConfigService;

	private ProductEmbeddingService productEmbeddingService;

	private String facetSearchConfigName;

	private int batchSize;

	@Override
	public PerformResult perform(CronJobModel cronJob) {
		IndexConfig indexConfig;
		try {
			indexConfig = this.facetSearchConfigService.getConfiguration(this.facetSearchConfigName).getIndexConfig();
		}
		catch (FacetConfigServiceException e) {
			LOG.error("No Solr facet search config {}", this.facetSearchConfigName, e);
			return new PerformResult(CronJobResult.ERROR, CronJobStatus.ABORTED);
		}
		int products = 0;
		int embedded = 0;
		for (IndexedType type : indexConfig.getIndexedTypes().values()) {
			// ponytail: every product of the index's catalog versions, not the indexer query's subset;
			// pass the full indexer query here if it filters out many products
			String select = "SELECT {pk} FROM {" + type.getComposedType().getCode()
					+ "} WHERE {catalogVersion} IN (?catalogVersions) ORDER BY {pk}";
			for (int start = 0;; start += this.batchSize) {
				if (clearAbortRequestedIfNeeded(cronJob)) {
					LOG.info("Aborted after {} products, {} texts embedded", products, embedded);
					return new PerformResult(CronJobResult.UNKNOWN, CronJobStatus.ABORTED);
				}
				FlexibleSearchQuery query = new FlexibleSearchQuery(select,
						Map.of("catalogVersions", indexConfig.getCatalogVersions()));
				query.setStart(start);
				query.setCount(this.batchSize);
				query.setNeedTotal(false);
				List<ProductModel> batch = this.flexibleSearchService.<ProductModel> search(query).getResult();
				if (batch.isEmpty()) {
					break;
				}
				try {
					embedded += this.productEmbeddingService.refresh(batch, indexConfig.getLanguages());
				}
				catch (RuntimeException e) {
					LOG.error("Embedding failed after {} products, {} texts embedded", products, embedded, e);
					return new PerformResult(CronJobResult.FAILURE, CronJobStatus.FINISHED);
				}
				products += batch.size();
				// 70,000 products would otherwise pile up in the session's model context
				this.modelService.detachAll();
			}
		}
		LOG.info("{} products checked, {} texts embedded", products, embedded);
		return new PerformResult(CronJobResult.SUCCESS, CronJobStatus.FINISHED);
	}

	@Override
	public boolean isAbortable() {
		return true;
	}

	public void setFacetSearchConfigService(FacetSearchConfigService facetSearchConfigService) {
		this.facetSearchConfigService = facetSearchConfigService;
	}

	public void setProductEmbeddingService(ProductEmbeddingService productEmbeddingService) {
		this.productEmbeddingService = productEmbeddingService;
	}

	public void setFacetSearchConfigName(String facetSearchConfigName) {
		this.facetSearchConfigName = facetSearchConfigName;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

}
