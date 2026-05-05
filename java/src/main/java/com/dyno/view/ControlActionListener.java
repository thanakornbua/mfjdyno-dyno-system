package com.dyno.view;

public interface ControlActionListener {
    void onStartRequested();

    void onStopRequested();

    void onRunModeRequested();

    void onPrintRequested();
}
