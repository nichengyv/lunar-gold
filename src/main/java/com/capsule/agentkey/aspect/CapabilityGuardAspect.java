package com.capsule.agentkey.aspect;

import com.capsule.agentkey.config.RequiresCapability;
import com.capsule.agentkey.service.CapabilityService;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class CapabilityGuardAspect {

    private final CapabilityService capabilityService;

    public CapabilityGuardAspect(CapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @Before("@within(requiresCapability)")
    public void checkType(RequiresCapability requiresCapability) {
        capabilityService.require(requiresCapability.value());
    }

    @Before("@annotation(requiresCapability)")
    public void checkMethod(RequiresCapability requiresCapability) {
        capabilityService.require(requiresCapability.value());
    }
}
