package dev.naoki.ethwhite.ponzi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public final class AnalysisClient {
    private final HttpClient httpClient;
    private final URI transferEndpoint;

    public AnalysisClient(String baseUrl) {
        this(URI.create(baseUrl));
    }

    public AnalysisClient(URI baseUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        this.transferEndpoint = resolveTransferEndpoint(Objects.requireNonNull(baseUrl, "baseUrl"));
    }

    public void postTransfer(FundFlowEvent event) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(transferEndpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(event.toJson()))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Analysis API returned HTTP " + response.statusCode());
        }
    }

    private static URI resolveTransferEndpoint(URI baseUrl) {
        String normalized = baseUrl.toString();
        if (normalized.endsWith("/api/v1/transfer")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized + "/api/v1/transfer");
    }
}
