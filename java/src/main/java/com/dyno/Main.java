package com.dyno;

import com.dyno.state.LiveTelemetryState;
import com.dyno.view.OperatorConsoleFrame;
import com.dyno.ws.DynoWebSocketClient;

import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        LiveTelemetryState telemetryState = new LiveTelemetryState();
        DynoWebSocketClient client = new DynoWebSocketClient(telemetryState);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                OperatorConsoleFrame window = new OperatorConsoleFrame(telemetryState, client);
                window.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent event) {
                        client.stop();
                    }
                });
                window.setVisible(true);
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                client.stop();
            }
        }, "dyno-ui-shutdown"));

        client.start();
    }
}
