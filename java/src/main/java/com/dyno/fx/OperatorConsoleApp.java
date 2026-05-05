package com.dyno.fx;

import com.dyno.config.EndpointConfig;
import javafx.application.Application;
import javafx.stage.Stage;

public final class OperatorConsoleApp extends Application {
    @Override
    public void start(Stage stage) {
        System.out.println(EndpointConfig.startupSummary());
        new OperatorConsoleStage().show(stage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
