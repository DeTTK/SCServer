package szmods.api;

/** Fixed-step callback data for deterministic simulation. */
public record ClientTick(Object game, double deltaSeconds, long number) {}
