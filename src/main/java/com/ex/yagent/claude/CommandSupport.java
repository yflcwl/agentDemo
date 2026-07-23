package com.ex.yagent.claude;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CommandSupport {

    private CommandSupport() {
    }

    public static CommandResult run(ProcessBuilder builder, long timeoutSeconds) {
        try {
            Process process = builder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try (InputStream inputStream = process.getInputStream()) {
                    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new CommandResult(false, "", true);
            }
            return new CommandResult(process.exitValue() == 0, outputFuture.get(5, TimeUnit.SECONDS).trim(), false);
        } catch (IOException e) {
            return new CommandResult(false, "Error: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, "Error: Interrupted", false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String message = cause == null ? e.getMessage() : cause.getMessage();
            return new CommandResult(false, "Error: " + message, false);
        } catch (TimeoutException e) {
            return new CommandResult(false, "Error: Output read timeout", false);
        }
    }

    public record CommandResult(boolean success, String output, boolean timedOut) {
    }
}
