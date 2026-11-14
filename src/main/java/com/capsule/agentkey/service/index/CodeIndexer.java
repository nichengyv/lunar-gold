package com.capsule.agentkey.service.index;

import java.util.List;

/**
 * 单文件符号提取的可插拔抽象。
 *
 * <p>v1 只有正则实现；后续接入 JavaParser / tree-sitter 时新增实现即可，
 * 扫描、缓存与 repo map 渲染都不需要改。</p>
 */
public interface CodeIndexer {

    /** 是否支持该扩展名（小写、不带点） */
    boolean supports(String extension);

    /** 提取文件内的符号；内容为空或无法解析时返回空列表 */
    List<CodeSymbol> index(String fileName, String content);
}
