package com.jdesk.demo;

import com.jdesk.core.config.PersistentStateComponent;
import com.jdesk.core.config.State;
import com.jdesk.core.config.Storage;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * Demo configuration that is persisted via ComponentStore.
 * Demonstrates PersistentStateComponent pattern.
 */
@State(name = "DemoConfig", storages = @Storage("DemoConfig.xml"))
public class DemoConfig implements PersistentStateComponent<DemoConfig.State> {

    private State currentState = new State();

    @Override
    public State getState() {
        // Update stats before saving
        currentState.lastSaveTimestamp = System.currentTimeMillis();
        currentState.launchCount++;
        return currentState;
    }

    @Override
    public void loadState(State state) {
        this.currentState = state;
    }

    @Override
    public State getDefaultState() {
        State defaults = new State();
        defaults.windowWidth = 1200;
        defaults.windowHeight = 800;
        defaults.frameworkVersion = "1.0-SNAPSHOT";
        return defaults;
    }

    // Getters for the current state
    public int getLaunchCount() { return currentState.launchCount; }
    public String getFrameworkVersion() { return currentState.frameworkVersion; }

    @JacksonXmlRootElement(localName = "DemoConfig")
    public static class State {

        @JacksonXmlProperty(isAttribute = true)
        public String frameworkVersion = "1.0-SNAPSHOT";

        @JacksonXmlProperty
        public int windowWidth = 1200;

        @JacksonXmlProperty
        public int windowHeight = 800;

        @JacksonXmlProperty
        public int launchCount = 0;

        @JacksonXmlProperty
        public long lastSaveTimestamp = 0;
    }
}
