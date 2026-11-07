package com.capsule.agentkey.controller;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.DeploymentMode;
import com.capsule.agentkey.dto.MetaResponse;
import com.capsule.agentkey.service.CapabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final CapabilityService capabilityService;
    private final String version;
    private final String latestDesktopVersion;

    public MetaController(CapabilityService capabilityService,
                          @Value("${app.version:1.0.0}") String version,
                          @Value("${app.download.latest-version:}") String latestDesktopVersion) {
        this.capabilityService = capabilityService;
        this.version = version;
        this.latestDesktopVersion = latestDesktopVersion;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        boolean downloadEnabled = capabilityService.has(Capability.DOWNLOAD);
        String latestVersion = downloadEnabled && StringUtils.hasText(latestDesktopVersion)
                ? latestDesktopVersion
                : null;
        String authMode = capabilityService.mode() == DeploymentMode.CLOUD
                ? "MULTI_USER"
                : "LOCAL_SINGLE_USER";
        return new MetaResponse(
                capabilityService.mode(),
                version,
                authMode,
                capabilityService.enabled(),
                new MetaResponse.Download(downloadEnabled, latestVersion));
    }
}
