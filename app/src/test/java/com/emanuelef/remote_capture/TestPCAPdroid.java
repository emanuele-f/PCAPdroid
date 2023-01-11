package com.emanuelef.remote_capture;

import org.robolectric.TestLifecycleApplication;

import java.lang.reflect.Method;

// NOTE: this class must be named "TestPCAPdroid"
// https://robolectric.org/custom-test-runner
public class TestPCAPdroid extends PCAPdroid implements TestLifecycleApplication {
    @Override
    public void onCreate() {
        PCAPdroid.isUnderTest = true;
        super.onCreate();
    }

    @Override
    public void beforeTest(Method method) {}

    @Override
    public void prepareTest(Object test) {}

    @Override
    public void afterTest(Method method) {}
}
