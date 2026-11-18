package szmods.api;

import java.nio.file.Path;
import java.util.function.Consumer;

public record ModContext(String id, Path configDirectory, Consumer<String> logger) {
    public void log(String message) { logger.accept(message); }
}
