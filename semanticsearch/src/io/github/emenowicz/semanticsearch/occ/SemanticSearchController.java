package io.github.emenowicz.semanticsearch.occ;

import jakarta.annotation.Resource;

import java.util.List;

import io.github.emenowicz.semanticsearch.SemanticAnswerService;
import io.github.emenowicz.semanticsearch.SemanticSearchService;
import io.github.emenowicz.semanticsearch.dto.SemanticAnswerWsDTO;
import io.github.emenowicz.semanticsearch.dto.SemanticSearchHitWsDTO;
import io.github.emenowicz.semanticsearch.dto.SemanticSearchResultWsDTO;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * {@code GET /occ/v2/{baseSiteId}/products/semantic-search?query=...&pageSize=10}: products by meaning, in the
 * session language ({@code lang} parameter). Public, like the platform's product search.
 */
@Controller
@RequestMapping("/{baseSiteId}/products/semantic-search")
public class SemanticSearchController {

	private static final int MAX_PAGE_SIZE = 50;

	@Resource(name = "semanticSearchService")
	private SemanticSearchService semanticSearchService;

	@Resource(name = "semanticAnswerService")
	private SemanticAnswerService semanticAnswerService;

	@GetMapping
	@ResponseBody
	// ponytail: no fields parameter / dataMapper field sets; add them when clients need smaller responses
	public SemanticSearchResultWsDTO search(@RequestParam String query, @RequestParam(defaultValue = "10") int pageSize) {
		validate(query);
		if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("pageSize must be 1.." + MAX_PAGE_SIZE);
		}
		SemanticSearchResultWsDTO result = new SemanticSearchResultWsDTO();
		result.setQuery(query);
		result.setProducts(toWsDTO(this.semanticSearchService.search(query.strip(), pageSize)));
		return result;
	}

	/**
	 * {@code GET .../products/semantic-search/answer?query=...}: a short answer grounded in the search results.
	 * Slow (a local model takes seconds), so it is separate from the search. A rejected or failed answer is a
	 * 200 with status FALLBACK and the products, since the search itself worked.
	 */
	@GetMapping("/answer")
	@ResponseBody
	public SemanticAnswerWsDTO answer(@RequestParam String query) {
		validate(query);
		SemanticAnswerService.Result result = this.semanticAnswerService.answer(query.strip());
		SemanticAnswerWsDTO dto = new SemanticAnswerWsDTO();
		dto.setQuery(query);
		dto.setStatus(result.status().name());
		dto.setAnswer(result.answer());
		dto.setReason(result.reason());
		dto.setCitedCodes(result.citedCodes());
		dto.setProducts(toWsDTO(result.products()));
		return dto;
	}

	// OCC answers IllegalArgumentException with 400 and passes the message on; the 503 and 429 come from
	// SemanticSearchUnavailableException and EmbeddingBudgetExceededException, mapped in project.properties
	private static void validate(String query) {
		if (query.isBlank()) {
			throw new IllegalArgumentException("query must not be blank");
		}
	}

	private static List<SemanticSearchHitWsDTO> toWsDTO(List<SemanticSearchService.Hit> hits) {
		return hits.stream().map(hit -> {
			SemanticSearchHitWsDTO dto = new SemanticSearchHitWsDTO();
			dto.setCode(hit.code());
			dto.setName(hit.name());
			dto.setScore(hit.score());
			return dto;
		}).toList();
	}

}
