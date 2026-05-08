package com.dyno.ws;

import com.dyno.config.EndpointConfig;
import com.dyno.model.FrameMessage;
import com.dyno.state.ConnectionPhase;
import com.dyno.state.LiveTelemetryState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class DynoWebSocketClient {
    private static final URI SOCKET_URI = EndpointConfig.wsUri();
    private static final int DEBUG_FRAME_LIMIT = 5;
    private static final String[] FRAME_WRAPPER_FIELDS = {"data", "payload", "frame"};

    private final LiveTelemetryState state;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicReference<WebSocket> currentSocket = new AtomicReference<WebSocket>();
    private final AtomicInteger debugFramesLogged = new AtomicInteger(0);
    private final AtomicBoolean rawDebugLogged = new AtomicBoolean(false);
    private final boolean rawDebugEnabled;

    public DynoWebSocketClient(LiveTelemetryState state) {
        this.state = state;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.mapper = new ObjectMapper();
        this.rawDebugEnabled = EndpointConfig.debugWebSocketFrames();
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dyno-ws-client");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            connect();
        }
    }

    static FrameMessage parseLiveFrameMessage(ObjectMapper mapper, String payload) throws java.io.IOException {
        JsonNode root = mapper.readTree(payload);
        JsonNode frameNode = findFrameNode(root);
        if (frameNode == null) {
            return null;
        }
        return mapper.treeToValue(frameNode, FrameMessage.class);
    }

    private static JsonNode findFrameNode(JsonNode root) {
        JsonNode current = root;
        for (int depth = 0; depth < 4 && current != null && current.isObject(); depth++) {
            JsonNode typeNode = current.get("type");
            if (typeNode != null && "live_frame".equals(typeNode.asText())) {
                JsonNode dataNode = current.get("data");
                if (dataNode == null || !dataNode.isObject()) {
                    return null;
                }
                if (hasLiveFrameField(dataNode)) {
                    return dataNode;
                }
                current = dataNode;
                continue;
            }
            if (hasLiveFrameField(current)) {
                return current;
            }
            JsonNode next = firstObjectChild(current);
            if (next == null || next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }

    private static JsonNode firstObjectChild(JsonNode node) {
        for (int index = 0; index < FRAME_WRAPPER_FIELDS.length; index++) {
            JsonNode child = node.get(FRAME_WRAPPER_FIELDS[index]);
            if (child != null && child.isObject()) {
                return child;
            }
        }
        return null;
    }

    private static boolean hasLiveFrameField(JsonNode node) {
        return node.has("engine_rpm")
            || node.has("roller_rpm")
            || node.has("speed_kmh")
            || node.has("power_hp")
            || node.has("torque_nm")
            || node.has("lambda")
            || node.has("afr")
            || node.has("ambient_temp_c")
            || node.has("humidity_pct")
            || node.has("pressure_hpa")
            || node.has("run_state");
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        WebSocket socket = currentSocket.getAndSet(null);
        if (socket != null) {
            socket.abort();
        }
        executor.shutdownNow();
        state.updateConnection(ConnectionPhase.DISCONNECTED, "Stopped");
    }

    public boolean sendCommand(String command) {
        return false;
    }

    public boolean sendRunCommand(String plateNumber) {
        return false;
    }

    public boolean sendCommand(String command, java.util.Map<String, Object> extraFields) {
        return false;
    }

    private void connect() {
        if (!running.get()) {
            return;
        }

        System.out.println("[dyno-ui] connecting to " + SOCKET_URI);
        state.updateConnection(ConnectionPhase.CONNECTING, "Connecting to " + SOCKET_URI);
        httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(SOCKET_URI, new ConnectionListener())
            .whenCompleteAsync((socket, error) -> {
                if (error != null) {
                    scheduleReconnect("Connect failed: " + rootMessage(error));
                    return;
                }
                currentSocket.set(socket);
            }, executor);
    }

    private void scheduleReconnect(String reason) {
        if (!running.get()) {
            return;
        }

        int attempt = reconnectAttempts.getAndIncrement();
        long delaySeconds = computeBackoffSeconds(attempt);
        System.out.println("[dyno-ui] " + reason + " Reconnecting in " + delaySeconds + "s.");
        state.updateConnection(
            ConnectionPhase.RECONNECT_WAIT,
            reason + " Reconnecting in " + delaySeconds + "s."
        );
        executor.schedule(new Runnable() {
            @Override
            public void run() {
                connect();
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private long computeBackoffSeconds(int attempt) {
        long delay = 1L << Math.min(attempt, 5);
        return Math.min(delay, 30L);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return (message == null || message.trim().isEmpty()) ? current.getClass().getSimpleName() : message;
    }

    private final class ConnectionListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private volatile String disconnectReason = "Disconnected";
        private volatile WebSocket socket;

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            reconnectAttempts.set(0);
            state.updateConnection(ConnectionPhase.CONNECTED, "Connected to " + SOCKET_URI);
            System.out.println("[dyno-ui] websocket connected to backend");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handleTextMessage(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handleTerminal("Connection error: " + rootMessage(error), true);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            String detail = disconnectReason;
            if (reason != null && !reason.trim().isEmpty()) {
                detail = reason;
            } else if (statusCode != 1000) {
                detail = "Connection closed (" + statusCode + ")";
            }
            handleTerminal(detail, true);
            return CompletableFuture.completedFuture(null);
        }

        private void handleTextMessage(String payload) {
            try {
                if (rawDebugLogged.compareAndSet(false, true)) {
                    System.out.println("[dyno-ui] raw websocket message (first frame): " + payload);
                }
                FrameMessage frame = parseLiveFrameMessage(mapper, payload);
                if (frame == null) {
                    return;
                }

                int debugIndex = debugFramesLogged.getAndIncrement();
                if (debugIndex < DEBUG_FRAME_LIMIT) {
                    System.out.println(
                        "[dyno-ui] live frame " + (debugIndex + 1)
                            + " rpm=" + frame.getEngineRpm()
                            + " roller=" + frame.getRollerRpm()
                            + " power=" + frame.getPowerHp()
                            + " torque=" + frame.getTorqueNm()
                            + " lambda=" + frame.getLambda()
                            + " afr=" + frame.getAfr()
                    );
                }

                state.updateFrame(frame);
            } catch (Exception error) {
                disconnectReason = "Frame parse error: " + rootMessage(error);
                WebSocket active = socket;
                if (active != null) {
                    active.abort();
                }
                handleTerminal(disconnectReason, true);
            }
        }

        private void handleTerminal(String reason, boolean reconnect) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }

            currentSocket.compareAndSet(socket, null);
            state.updateConnection(
                reconnect ? ConnectionPhase.RECONNECT_WAIT : ConnectionPhase.DISCONNECTED,
                reason
            );

            if (reconnect) {
                scheduleReconnect(reason);
            }
        }
    }
}
