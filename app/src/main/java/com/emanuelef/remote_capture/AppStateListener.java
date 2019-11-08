package com.emanuelef.remote_capture;

interface AppStateListener {
    void appStateReady();
    void appStateStarting();
    void appStateRunning();
    void appStateStopping();
}
