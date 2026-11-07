package com.capsule.agentkey.service;

import com.capsule.agentkey.config.Capability;
import com.capsule.agentkey.config.DeploymentMode;
import com.capsule.agentkey.exception.BusinessException;
import com.capsule.agentkey.exception.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

@Service
public class CapabilityService {

    private static final Set<Capability> CLOUD_CAPABILITIES = Collections.unmodifiableSet(
            EnumSet.of(Capability.CHAT, Capability.KNOWLEDGE, Capability.DOWNLOAD));
    private static final Set<Capability> DESKTOP_CAPABILITIES;

    static {
        EnumSet<Capability> capabilities = EnumSet.allOf(Capability.class);
        capabilities.remove(Capability.DOWNLOAD);
        DESKTOP_CAPABILITIES = Collections.unmodifiableSet(capabilities);
    }

    private final DeploymentMode mode;
    private final Set<Capability> enabled;

    public CapabilityService(Environment environment) {
        this.mode = environment.acceptsProfiles(Profiles.of("desktop"))
                ? DeploymentMode.DESKTOP
                : DeploymentMode.CLOUD;
        this.enabled = mode == DeploymentMode.DESKTOP ? DESKTOP_CAPABILITIES : CLOUD_CAPABILITIES;
    }

    public DeploymentMode mode() {
        return mode;
    }

    public Set<Capability> enabled() {
        return enabled;
    }

    public boolean has(Capability capability) {
        return enabled.contains(capability);
    }

    public void require(Capability capability) {
        if (!has(capability)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "当前部署模式未启用该功能");
        }
    }
}
