package szmods.api;

/** game is the current native game instance. Native classes remain build-specific. */
public record ClientFrame(Object game, double deltaSeconds, long number) {}
