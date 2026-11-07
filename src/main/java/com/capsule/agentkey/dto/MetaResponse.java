package com.capsule.agentkey.dto;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.DeploymentMode;

import java.util.Set;

public record MetaResponse(
        DeploymentMode mode,
        String version,
        String authMode,
        Set<Capability> capabilities,
        Download download) {

    public record Download(boolean enabled, String latestVersion) {
    }
}
