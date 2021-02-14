package com.emanuelef.remote_capture.model;

/* App state handling: ready -> starting -> running -> stopping -> ready  */
public enum AppState {
    ready,
    starting,
    running,
    stopping
}
