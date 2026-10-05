package io.github.emenowicz.semanticsearch;

/**
 * The per-minute cap on embedding API calls is used up. OCC maps it to 429 by class name (project.properties).
 * Not an IllegalStateException on purpose: that one means "unavailable" (503).
 */
public class EmbeddingBudgetExceededException extends RuntimeException {

	public EmbeddingBudgetExceededException(String message) {
		super(message);
	}

}
