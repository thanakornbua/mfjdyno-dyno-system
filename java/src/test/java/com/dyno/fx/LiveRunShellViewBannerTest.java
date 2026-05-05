package com.dyno.fx;

import com.dyno.health.OperatorStatusModel;
import com.dyno.presenter.OperatorViewModel;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public final class LiveRunShellViewBannerTest {
    @Test
    public void faultBannerWinsOverBackendUnavailable() {
        OperatorViewModel.BannerModel resolved = LiveRunShellView.resolveBanner(
            model("CONNECTED", "FAULT", banner("FAULT", "Sensor fault", OperatorViewModel.Tone.FAULT)),
            backendStatus(OperatorStatusModel.OverallState.UNAVAILABLE, "Backend unavailable", "Retrying"),
            new RunControlUiState()
        );

        assertEquals("FAULT", resolved.getTitle());
        assertEquals("Sensor fault", resolved.getMessage());
        assertEquals(OperatorViewModel.Tone.FAULT, resolved.getTone());
    }

    @Test
    public void reconnectBannerWinsOverBackendDegradedAndKeepsLatestMessage() {
        OperatorViewModel.BannerModel resolved = LiveRunShellView.resolveBanner(
            model(
                "RECONNECTING",
                "IDLE",
                banner("RECONNECTING", "Connect failed: refused Reconnecting in 4s.", OperatorViewModel.Tone.CAUTION)
            ),
            backendStatus(OperatorStatusModel.OverallState.DEGRADED, "Serial input unavailable", "1 startup warning active"),
            new RunControlUiState()
        );

        assertEquals("RECONNECTING", resolved.getTitle());
        assertEquals("Connect failed: refused Reconnecting in 4s.", resolved.getMessage());
        assertEquals(OperatorViewModel.Tone.CAUTION, resolved.getTone());
    }

    @Test
    public void backendDegradedWinsOverOperatorMessage() {
        RunControlUiState controlState = new RunControlUiState();
        controlState.showOperatorMessage("Saved calibration profile.", OperatorViewModel.Tone.NORMAL);

        OperatorViewModel.BannerModel resolved = LiveRunShellView.resolveBanner(
            model("CONNECTED", "IDLE", banner("CONNECTED", "Ready", OperatorViewModel.Tone.NORMAL)),
            backendStatus(OperatorStatusModel.OverallState.DEGRADED, "Serial input unavailable", "1 startup warning active"),
            controlState
        );

        assertEquals("BACKEND DEGRADED", resolved.getTitle());
        assertEquals("Serial input unavailable", resolved.getMessage());
        assertEquals(OperatorViewModel.Tone.CAUTION, resolved.getTone());
    }

    @Test
    public void connectedOperatorAlertWinsOverNormalRunState() {
        RunControlUiState controlState = new RunControlUiState();
        controlState.applyNetworkError("Run control request failed.");

        OperatorViewModel.BannerModel resolved = LiveRunShellView.resolveBanner(
            model("CONNECTED", "IDLE", banner("CONNECTED", "Ready", OperatorViewModel.Tone.NORMAL)),
            backendStatus(OperatorStatusModel.OverallState.READY, "Backend ready", "Health checks passing"),
            controlState
        );

        assertEquals("OPERATOR MESSAGE", resolved.getTitle());
        assertEquals("Run control request failed.", resolved.getMessage());
        assertEquals(OperatorViewModel.Tone.ALERT, resolved.getTone());
    }

    private static OperatorStatusModel backendStatus(
        OperatorStatusModel.OverallState state,
        String primary,
        String secondary
    ) {
        return new OperatorStatusModel(
            state,
            state != OperatorStatusModel.OverallState.UNAVAILABLE,
            "live",
            true,
            false,
            false,
            primary,
            secondary,
            null,
            Collections.<String>emptyList()
        );
    }

    private static OperatorViewModel model(
        String connection,
        String state,
        OperatorViewModel.BannerModel banner
    ) {
        OperatorViewModel.GaugeModel gauge = new OperatorViewModel.GaugeModel(
            "Gauge",
            "—",
            "",
            "",
            OperatorViewModel.Tone.UNAVAILABLE
        );
        OperatorViewModel.MetricTileModel tile = new OperatorViewModel.MetricTileModel(
            "Metric",
            "—",
            "",
            "",
            OperatorViewModel.Tone.UNAVAILABLE
        );

        return new OperatorViewModel(
            connection,
            state,
            "RUN",
            "PLATE",
            "Chart",
            "CAN missing",
            "— HP",
            "— Nm",
            false,
            true,
            false,
            true,
            true,
            banner,
            gauge,
            gauge,
            gauge,
            tile,
            tile,
            tile,
            tile,
            Collections.<OperatorViewModel.SecondaryMetricModel>emptyList(),
            Collections.<com.dyno.model.RunPoint>emptyList()
        );
    }

    private static OperatorViewModel.BannerModel banner(
        String title,
        String message,
        OperatorViewModel.Tone tone
    ) {
        return new OperatorViewModel.BannerModel(title, message, tone);
    }
}
