package io.github.emenowicz.semanticsearch;

/**
 * Embedding API down or rate limited, or no index. OCC maps exceptions to HTTP status by simple class name
 * (webservicescommons.resthandlerexceptionresolver.* in project.properties): this one is a 503.
 */
public class SemanticSearchUnavailableException extends RuntimeException {

	public SemanticSearchUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
