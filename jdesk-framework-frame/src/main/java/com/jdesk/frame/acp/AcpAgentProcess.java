package com.jdesk.frame.acp;

import com.jdesk.frame.sidecar.JsonlSidecarProcess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** ACP agent sidecar process: a {@link JsonlSidecarProcess} named for ACP; {@link AcpClient} speaks the protocol. */
public final class AcpAgentProcess extends JsonlSidecarProcess {

    public AcpAgentProcess(List<String> command, Path workingDir, Map<String, String> extraEnv) {
        super("acp-agent", command, workingDir, extraEnv);
    }
}
