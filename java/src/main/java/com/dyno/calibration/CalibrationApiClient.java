package com.dyno.calibration;

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
import java.util.Map;

public final class CalibrationApiClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public CalibrationApiClient(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        this.mapper = new ObjectMapper();
    }

    public static CalibrationApiClient fromEnvironment() {
        return new CalibrationApiClient(EndpointConfig.apiBaseUri());
    }

    public CalibrationResponseDto getActiveCalibration() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration")
            .GET()
            .build();
        return send(request, CalibrationResponseDto.class);
    }

    public List<CalibrationProfileDto> listCalibrationProfiles() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration/profiles")
            .GET()
            .build();
        return send(request, new TypeReference<List<CalibrationProfileDto>>() { });
    }

    public CalibrationResponseDto createCalibrationProfile(CalibrationUpsertRequestDto requestDto) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration/profiles")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestDto)))
            .build();
        return send(request, CalibrationResponseDto.class);
    }

    public CalibrationResponseDto updateCalibrationProfile(long profileId, CalibrationUpsertRequestDto requestDto) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration/profiles/" + profileId)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestDto)))
            .build();
        return send(request, CalibrationResponseDto.class);
    }

    public CalibrationResponseDto duplicateCalibrationProfile(long profileId, DuplicateCalibrationProfileRequestDto requestDto) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration/profiles/" + profileId + "/duplicate")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestDto)))
            .build();
        return send(request, CalibrationResponseDto.class);
    }

    public CalibrationResponseDto activateCalibration(long profileId) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration/activate")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(new ActivateCalibrationRequestDto(Long.valueOf(profileId)))
            ))
            .build();
        return send(request, CalibrationResponseDto.class);
    }

    public List<CalibrationProfileEventDto> listCalibrationProfileEvents(long profileId) throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/calibration/profiles/" + profileId + "/events")
            .GET()
            .build();
        return send(request, new TypeReference<List<CalibrationProfileEventDto>>() { });
    }

    public void lockCalibration(String password) throws IOException, InterruptedException, LockException {
        sendLockRequest("/api/calibration/lock", password);
    }

    public void unlockCalibration(String password) throws IOException, InterruptedException, LockException {
        sendLockRequest("/api/calibration/unlock", password);
    }

    public List<AuditRecordDto> listAuditRecords() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/audit")
            .GET()
            .build();
        return send(request, new TypeReference<List<AuditRecordDto>>() { });
    }

    public void changePassword(String currentPassword, String newPassword)
            throws IOException, InterruptedException, LockException {
        String body = mapper.writeValueAsString(Map.of(
            "current_password", currentPassword,
            "new_password", newPassword
        ));
        HttpRequest request = requestBuilder("/api/system/password")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 400) {
            throw new LockException(response.statusCode(), extractErrorMessage(response.body()));
        }
        ensureSuccess(response.statusCode(), response.body());
    }

    public void verifyPassword(String password) throws IOException, InterruptedException, LockException {
        String body = mapper.writeValueAsString(Map.of("password", password));
        HttpRequest request = requestBuilder("/api/system/verify-password")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            throw new LockException(response.statusCode(), "Incorrect password");
        }
        ensureSuccess(response.statusCode(), response.body());
    }

    private String sendLockRequest(String path, String password) throws IOException, InterruptedException, LockException {
        String body = mapper.writeValueAsString(Map.of("password", password));
        HttpRequest request = requestBuilder(path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 423) {
            throw new LockException(response.statusCode(), response.body());
        }
        ensureSuccess(response.statusCode(), response.body());
        return response.body();
    }

    public static final class LockException extends Exception {
        public final int statusCode;

        public LockException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
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

    private String extractErrorMessage(String body) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                JsonNode json = mapper.readTree(body);
                JsonNode error = json.get("error");
                if (error != null && !error.asText().trim().isEmpty()) {
                    return error.asText().trim();
                }
            } catch (IOException ignored) {
            }
        }
        return body != null ? body : "Unknown error";
    }

    private void ensureSuccess(int statusCode, String body) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }

        String message = "Calibration API returned HTTP " + statusCode;
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
