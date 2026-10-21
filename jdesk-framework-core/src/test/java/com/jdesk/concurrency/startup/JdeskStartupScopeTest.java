package com.jdesk.concurrency.startup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdeskStartupScopeTest {

    @Test
    void asyncTasksCanBeAwaitedFromJava() throws Exception {
        try (JdeskStartupScope scope = new JdeskStartupScope("test-startup")) {
            List<StartupDeferred<Integer>> tasks = new ArrayList<>();
            tasks.add(scope.asyncIo("one", () -> 1));
            tasks.add(scope.asyncDefault("two", () -> 2));

            assertEquals(List.of(1, 2), scope.awaitAll(tasks));
        }
    }
}
