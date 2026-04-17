package com.dyno.operator.config.client;

import com.dyno.operator.config.model.Esp32DaqConfigResponseDto;
import com.dyno.operator.config.model.Esp32DaqConfigUpdateRequestDto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class HttpEsp32DaqConfigClient implements Esp32DaqConfigClient {
    private static final String CONFIG_PATH = "/api/esp32/config";

    private final HttpClient httpClient;
    private final URI apiBaseUri;
    private final JsonCodec jsonCodec;

    public HttpEsp32DaqConfigClient(HttpClient httpClient, URI apiBaseUri, JsonCodec jsonCodec) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.apiBaseUri = Objects.requireNonNull(apiBaseUri, "apiBaseUri");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    @Override
    public CompletableFuture<Esp32DaqConfigResponseDto> loadCurrentConfig() {
        HttpRequest request = HttpRequest.newBuilder(resolve(CONFIG_PATH))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .GET()
            .build();

        return send(request);
    }

    @Override
    public CompletableFuture<Esp32DaqConfigResponseDto> submitConfig(Esp32DaqConfigUpdateRequestDto requestDto) {
        String payload = jsonCodec.write(requestDto);
        HttpRequest request = HttpRequest.newBuilder(resolve(CONFIG_PATH))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
            .build();

        return send(request);
    }

    private CompletableFuture<Esp32DaqConfigResponseDto> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .thenApply(response -> {
                int status = response.statusCode();
                String body = response.body();
                if (status < 200 || status >= 300) {
                    throw new CompletionException(new IOException(
                        "ESP32 config API request failed with status " + status + ": " + body
                    ));
                }
                return jsonCodec.read(body, Esp32DaqConfigResponseDto.class);
            });
    }

    private URI resolve(String path) {
        String base = apiBaseUri.toString();
        if (base.endsWith("/")) {
            return URI.create(base.substring(0, base.length() - 1) + path);
        }
        return URI.create(base + path);
    }
}
