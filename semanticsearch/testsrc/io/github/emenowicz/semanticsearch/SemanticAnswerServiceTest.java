package io.github.emenowicz.semanticsearch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.LanguageModel;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;

import io.github.emenowicz.semanticsearch.SemanticAnswerService.Result;
import io.github.emenowicz.semanticsearch.SemanticAnswerService.Status;

import org.junit.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** The grounding guarantee: an answer survives only if every product it cites came from the search. */
@UnitTest
public class SemanticAnswerServiceTest {

	private static final List<SemanticSearchService.Hit> FOUND = List.of(
			new SemanticSearchService.Hit("eval-p01", "SDS-plus drill, 750 W", "Drills into concrete.", 0.64),
			new SemanticSearchService.Hit("eval-p02", "Percussion drill, 650 W", "Drills into brick.", 0.61));

	@Test
	public void answerCitingRetrievedProductsIsKept() {
		Result result = SemanticAnswerService.validate(
				"{\"answer\": \"The 650 W percussion drill fits.\", \"productCodes\": [\"eval-p02\"]}", FOUND);

		assertEquals(Status.ANSWERED, result.status());
		assertEquals(List.of("eval-p02"), result.citedCodes());
		assertEquals(2, result.products().size());
	}

	@Test
	public void answerCitingAnInventedProductFallsBackToTheList() {
		Result result = SemanticAnswerService.validate(
				"{\"answer\": \"Take the X-900.\", \"productCodes\": [\"eval-p02\", \"x-900\"]}", FOUND);

		assertEquals(Status.FALLBACK, result.status());
		assertNull(result.answer());
		assertTrue(result.reason(), result.reason().contains("x-900"));
		assertEquals(2, result.products().size());
	}

	@Test
	public void proseInsteadOfJsonFallsBack() {
		Result result = SemanticAnswerService.validate("Sure! I recommend the drill.", FOUND);

		assertEquals(Status.FALLBACK, result.status());
	}

	@Test
	public void noFittingProductIsAValidAnswer() {
		Result result = SemanticAnswerService.validate(
				"```json\n{\"answer\": \"We have no lawn mowers.\", \"productCodes\": []}\n```", FOUND);

		assertEquals(Status.ANSWERED, result.status());
		assertTrue(result.citedCodes().isEmpty());
	}

	@Test
	public void codesAndShortQueriesAreNotWorthAnAnswer() {
		for (String query : List.of("6204-2RS", "SPZ 1250", "DIN 912 M8x30 A2", "OR 30x3 NBR", "brugola M5 x 16")) {
			assertFalse(query, SemanticAnswerService.worthAnswering(query, 2));
		}
		for (String query : List.of("disjoncteur moteur 12 A", "grease for electric motor bearings",
				"lebensmittelechtes Fett für Bäckereimaschine")) {
			assertTrue(query, SemanticAnswerService.worthAnswering(query, 2));
		}
	}

	@Test
	public void shortQueryGetsTheListWithoutAModelCall() {
		AtomicInteger calls = new AtomicInteger();
		Result result = service(calls, 0).answer("6204-2RS");

		assertEquals(Status.SKIPPED, result.status());
		assertEquals(2, result.products().size());
		assertEquals(0, calls.get());
	}

	@Test
	public void repeatedQuestionCallsTheModelOnce() {
		AtomicInteger calls = new AtomicInteger();
		SemanticAnswerService service = service(calls, 0);

		Result first = service.answer("drill for concrete walls");
		Result again = service.answer("  Drill for   concrete walls ");

		assertEquals(Status.ANSWERED, first.status());
		assertEquals(first.answer(), again.answer());
		assertEquals(1, calls.get());
	}

	@Test
	public void spentBudgetGivesTheListWithoutAModelCall() {
		AtomicInteger calls = new AtomicInteger();
		SemanticAnswerService service = service(calls, 1);

		assertEquals(Status.ANSWERED, service.answer("drill for concrete walls").status());
		Result overBudget = service.answer("drill for brick walls");

		assertEquals(Status.FALLBACK, overBudget.status());
		assertEquals(2, overBudget.products().size());
		assertEquals(1, calls.get());
	}

	private static SemanticAnswerService service(AtomicInteger modelCalls, int maxAnswersPerDay) {
		LanguageModel english = mock(LanguageModel.class);
		when(english.getIsocode()).thenReturn("en");
		CommonI18NService i18n = mock(CommonI18NService.class);
		when(i18n.getCurrentLanguage()).thenReturn(english);

		SemanticAnswerService service = new SemanticAnswerService();
		service.setSemanticSearchService(new SemanticSearchService() {
			@Override
			public List<Hit> search(String query, int topK) {
				return FOUND;
			}
		});
		service.setChatModel(prompt -> {
			modelCalls.incrementAndGet();
			return new ChatResponse(List.of(new Generation(new AssistantMessage(
					"{\"answer\": \"The SDS-plus drill fits.\", \"productCodes\": [\"eval-p01\"]}"))));
		});
		service.setCommonI18NService(i18n);
		service.setTopK(8);
		service.setMinWords(2);
		service.setCacheSize(100);
		service.setMaxAnswersPerDay(maxAnswersPerDay);
		return service;
	}

}
