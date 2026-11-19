package com.capsule.agentkey.dto;

import com.capsule.agentkey.exception.BusinessException;

import java.util.List;

/**
 * P6-01：创建客户端的请求体。
 *
 * scopes 必填，且必须全部是已知 scope；故意不给默认值，强制调用方想清楚要给客户端多大权限。
 */
public record CreateClientRequest(String name, List<String> scopes) {

    public CreateClientRequest {
        if (name == null || name.isBlank()) {
            throw new BusinessException("客户端名称不能为空");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new BusinessException("至少需要申请一个 scope");
        }
    }
}
