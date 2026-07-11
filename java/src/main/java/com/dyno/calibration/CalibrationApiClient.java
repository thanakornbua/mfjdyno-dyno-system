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
    // The backend's dependency check shells out to `arduino-cli core list`,
    // which can take several seconds; give it a longer budget than other
    // endpoints so a slow-but-successful check isn't reported as a timeout.
    private static final Duration DEPENDENCY_REQUEST_TIMEOUT = Duration.ofSeconds(15);

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
        if (response.statusCode() == 401 || response.statusCode() == 400 || response.statusCode() == 409) {
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
        if (response.statusCode() == 409) {
            throw new LockException(response.statusCode(), extractErrorMessage(response.body()));
        }
        ensureSuccess(response.statusCode(), response.body());
    }

    public SetupStatusDto getSetupStatus() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/system/setup-status")
            .GET()
            .build();
        return send(request, SetupStatusDto.class);
    }

    public SerialDevicesDto listSerialDevices() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/system/serial-devices")
            .GET()
            .build();
        return send(request, SerialDevicesDto.class);
    }

    public DependencyStatusDto listDependencies() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/system/dependencies", DEPENDENCY_REQUEST_TIMEOUT)
            .GET()
            .build();
        return send(request, DependencyStatusDto.class);
    }

    public void saveDevices(String readSerialPort, String flashSerialPort)
            throws IOException, InterruptedException, LockException {
        java.util.Map<String, String> payload = new java.util.HashMap<>();
        if (readSerialPort != null && !readSerialPort.isBlank()) {
            payload.put("read_serial_port", readSerialPort);
        }
        if (flashSerialPort != null && !flashSerialPort.isBlank()) {
            payload.put("flash_serial_port", flashSerialPort);
        }
        HttpRequest request = requestBuilder("/api/system/devices")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 409) {
            throw new LockException(response.statusCode(), extractErrorMessage(response.body()));
        }
        ensureSuccess(response.statusCode(), response.body());
    }

    /** Starts an ESP firmware flash. {@code flashSerialPort} may be null to use the persisted port. */
    public void flashEsp(String flashSerialPort) throws IOException, InterruptedException, LockException {
        java.util.Map<String, String> payload = new java.util.HashMap<>();
        if (flashSerialPort != null && !flashSerialPort.isBlank()) {
            payload.put("flash_serial_port", flashSerialPort);
        }
        HttpRequest request = requestBuilder("/api/system/flash-esp")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 409) {
            throw new LockException(response.statusCode(), extractErrorMessage(response.body()));
        }
        ensureSuccess(response.statusCode(), response.body());
    }

    public FlashStatusDto getFlashStatus() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/api/system/flash-esp/status")
            .GET()
            .build();
        return send(request, FlashStatusDto.class);
    }

    public void setupPassword(String newPassword) throws IOException, InterruptedException, LockException {
        String body = mapper.writeValueAsString(Map.of("new_password", newPassword));
        HttpRequest request = requestBuilder("/api/system/setup-password")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 409) {
            throw new LockException(response.statusCode(), extractErrorMessage(response.body()));
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
        if (response.statusCode() == 409) {
            throw new LockException(response.statusCode(), extractErrorMessage(response.body()));
        }
        ensureSuccess(response.statusCode(), response.body());
        return response.body();
    }

    /**
     * Thrown for 401 (wrong password), 423 (calibration locked), and 409
     * (system password not set up yet — see {@link #isSetupRequired()}).
     */
    public static final class LockException extends Exception {
        public final int statusCode;

        public LockException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public boolean isSetupRequired() {
            return statusCode == 409;
        }
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return requestBuilder(path, REQUEST_TIMEOUT);
    }

    private HttpRequest.Builder requestBuilder(String path, Duration timeout) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
            .timeout(timeout)
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
