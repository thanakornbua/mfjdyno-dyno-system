package com.dyno.history;

import com.dyno.config.EndpointConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class HistoryApiClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public HistoryApiClient(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.mapper = new ObjectMapper();
    }

    public static HistoryApiClient fromEnvironment() {
        return new HistoryApiClient(EndpointConfig.apiBaseUri());
    }

    public List<RunHistorySummaryDto> listRuns() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/runs")
            .GET()
            .build();
        return send(request, new TypeReference<List<RunHistorySummaryDto>>() { });
    }

    public RunHistoryDetailDto getRun(long runId) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/runs/" + runId)
            .GET()
            .build();
        return send(request, RunHistoryDetailDto.class);
    }

    public RunHistoryFrameSeriesDto getRunFrames(long runId) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/runs/" + runId + "/frames")
            .GET()
            .build();
        return send(request, RunHistoryFrameSeriesDto.class);
    }

    public CompareRunsResponseDto compareRuns(List<Long> runIds) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/runs/compare")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(new CompareRunsRequestDto(runIds))
            ))
            .build();
        return send(request, CompareRunsResponseDto.class);
    }

    public boolean deleteRun(long runId) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/runs/" + runId)
            .DELETE()
            .build();
        DeleteRunResponseDto response = send(request, DeleteRunResponseDto.class);
        return response.getDeleted() != null && response.getDeleted().booleanValue();
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json");
    }

    private <T> T send(HttpRequest request, Class<T> responseType) throws IOException, InterruptedException {
        try {
            return doSend(request, responseType);
        } catch (IOException e) {
            if (causedByClosedChannel(e)) {
                return doSend(request, responseType);
            }
            throw e;
        }
    }

    private <T> T send(HttpRequest request, TypeReference<T> responseType) throws IOException, InterruptedException {
        try {
            return doSend(request, responseType);
        } catch (IOException e) {
            if (causedByClosedChannel(e)) {
                return doSend(request, responseType);
            }
            throw e;
        }
    }

    private <T> T doSend(HttpRequest request, Class<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());
        return mapper.readValue(response.body(), responseType);
    }

    private <T> T doSend(HttpRequest request, TypeReference<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());
        return mapper.readValue(response.body(), responseType);
    }

    private void ensureSuccess(int statusCode, String body) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        String message = "History API returned HTTP " + statusCode;
        if (body != null && !body.trim().isEmpty()) {
            try {
                JsonNode json = mapper.readTree(body);
                JsonNode error = json.get("error");
                if (error != null && !error.asText().trim().isEmpty()) {
                    message = error.asText().trim();
                }
            } catch (IOException ignored) {
                String trimmed = body.length() > 180 ? body.substring(0, 180) + "..." : body;
                message = message + ": " + trimmed;
            }
        }
        throw new IOException(message);
    }

    private static boolean causedByClosedChannel(Throwable t) {
        while (t != null) {
            if (t instanceof java.nio.channels.ClosedChannelException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
