package com.dyno.health;

import com.dyno.config.EndpointConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class HealthApiClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public HealthApiClient(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.mapper = new ObjectMapper();
    }

    public static HealthApiClient fromEnvironment() {
        return new HealthApiClient(EndpointConfig.apiBaseUri());
    }

    public StartupHealthDto getStartupHealth() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/healthz"))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
        return send(request);
    }

    private StartupHealthDto send(HttpRequest request) throws IOException, InterruptedException {
        try {
            return doSend(request);
        } catch (IOException e) {
            if (causedByClosedChannel(e)) {
                return doSend(request);
            }
            throw e;
        }
    }

    private StartupHealthDto doSend(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ensureSuccess(response.statusCode(), response.body());
        return mapper.readValue(response.body(), StartupHealthDto.class);
    }

    private void ensureSuccess(int statusCode, String body) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        String message = "Health API returned HTTP " + statusCode;
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
