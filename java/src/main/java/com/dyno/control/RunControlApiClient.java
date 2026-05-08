package com.dyno.control;

import com.dyno.config.EndpointConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class RunControlApiClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public RunControlApiClient(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.mapper = new ObjectMapper();
    }

    public static RunControlApiClient fromEnvironment() {
        return new RunControlApiClient(EndpointConfig.controlApiBaseUri());
    }

    public RunControlResponse configure(RunConfigureRequest request)
        throws IOException, InterruptedException {
        return sendPost("/api/run/configure", mapper.writeValueAsString(request), true);
    }

    public RunControlResponse start() throws IOException, InterruptedException {
        return sendPost("/api/run/start", "", false);
    }

    public RunControlResponse stop() throws IOException, InterruptedException {
        return sendPost("/api/run/stop", "", false);
    }

    public RunControlResponse status() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/api/run/status"))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseResponse(response.statusCode(), response.body());
    }

    private RunControlResponse sendPost(String path, String body, boolean hasJsonBody)
        throws IOException, InterruptedException {
        try {
            return doSendPost(path, body, hasJsonBody);
        } catch (IOException e) {
            if (causedByClosedChannel(e)) {
                // Stale keep-alive connection reused for POST — retry once with a fresh connection.
                return doSendPost(path, body, hasJsonBody);
            }
            throw e;
        }
    }

    private RunControlResponse doSendPost(String path, String body, boolean hasJsonBody)
        throws IOException, InterruptedException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(REQUEST_TIMEOUT);
        if (hasJsonBody) {
            requestBuilder.header("Content-Type", "application/json");
        }
        HttpRequest request = requestBuilder
            .POST(hasJsonBody ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody())
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseResponse(response.statusCode(), response.body());
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

    private RunControlResponse parseResponse(int statusCode, String body) {
        if (body == null || body.trim().isEmpty()) {
            return RunControlResponse.failure(statusCode, "Control API returned HTTP " + statusCode);
        }
        try {
            RunControlResponse parsed = mapper.readValue(body, RunControlResponse.class);
            parsed.normalizeFallbacks(statusCode);
            return parsed;
        } catch (IOException error) {
            String text = body.length() > 160 ? body.substring(0, 160) + "..." : body;
            return RunControlResponse.failure(
                statusCode,
                "Unable to parse control API response (HTTP " + statusCode + "): " + text
            );
        }
    }
}
