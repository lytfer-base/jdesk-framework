package com.jdesk.frame.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Fake sidecar for the process wrapper tests: sends a hello line on start, then echoes each line; "stderr" writes a
 * line to stderr and "exit" exits with code 7.
 */
public final class FakeJsonlSidecar {

    private FakeJsonlSidecar() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        out.println(json.createObjectNode().put("hello", "fake").toString());
        String line;
        while ((line = in.readLine()) != null) {
            switch (line) {
                case "stderr" -> err.println("warned");
                case "exit" -> System.exit(7);
                default -> out.println(json.createObjectNode().put("echo", line).toString());
            }
        }
    }
}
