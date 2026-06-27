package dev.naoki.ethwhite.ponzi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class FundFlowEmitter implements FundFlowListener, AutoCloseable {
    private final AnalysisClient analysisClient;
    private final Path pendingFile;
    private final ExecutorService executor;

    public FundFlowEmitter(AnalysisClient analysisClient, Path pendingFile) {
        this.analysisClient = Objects.requireNonNull(analysisClient, "analysisClient");
        this.pendingFile = Objects.requireNonNull(pendingFile, "pendingFile");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "fund-flow-emitter");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static FundFlowEmitter fromSystemProperties() {
        String baseUrl = System.getProperty("analysis.api.url", "http://localhost:8000");
        Path pendingFile = Path.of(System.getProperty("analysis.pending.file", "data/pending_transfers.jsonl"));
        return new FundFlowEmitter(new AnalysisClient(baseUrl), pendingFile);
    }

    @Override
    public void onTransfer(FundFlowEvent event) {
        executor.submit(() -> dispatch(event));
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void dispatch(FundFlowEvent event) {
        try {
            analysisClient.postTransfer(event);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            appendPending(event);
        }
    }

    private void appendPending(FundFlowEvent event) {
        try {
            Path parent = pendingFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    pendingFile,
                    event.toJson() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Detection is best-effort and must not affect block validation.
        }
    }
}
