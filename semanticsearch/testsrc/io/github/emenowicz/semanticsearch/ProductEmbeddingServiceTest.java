package io.github.emenowicz.semanticsearch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import de.hybris.bootstrap.annotations.UnitTest;

import org.junit.Test;

/** What decides whether a stored vector is reused: the stored bytes and the staleness hash. */
@UnitTest
public class ProductEmbeddingServiceTest {

	@Test
	public void vectorSurvivesStorage() {
		float[] vector = { 0.25f, -1.5f, Float.MIN_VALUE, 3.4e38f };

		assertArrayEquals(vector, ProductEmbeddingService.decode(ProductEmbeddingService.encode(vector)), 0f);
	}

	@Test
	public void sameTextAndModelIsUpToDate() {
		assertEquals(ProductEmbeddingService.sourceHash("ollama|bge-m3", "Rillenkugellager 6204"),
				ProductEmbeddingService.sourceHash("ollama|bge-m3", "Rillenkugellager 6204"));
	}

	@Test
	public void changedTextIsStale() {
		assertNotEquals(ProductEmbeddingService.sourceHash("ollama|bge-m3", "Rillenkugellager 6204"),
				ProductEmbeddingService.sourceHash("ollama|bge-m3", "Rillenkugellager 6204-2RS"));
	}

	@Test
	public void changedModelIsStale() {
		assertNotEquals(ProductEmbeddingService.sourceHash("ollama|bge-m3", "Rillenkugellager 6204"),
				ProductEmbeddingService.sourceHash("voyage|voyage-4-large", "Rillenkugellager 6204"));
	}

}
