package com.capsule.agentkey.service.index;

/**
 * 代码索引中的一个符号。
 *
 * <p>{@code kind} 只影响展示：repo map 里包、方法、Vue 组件需要加上前后缀，
 * 其余符号直接输出名称。这样索引本身不关心排版。</p>
 */
public record CodeSymbol(String kind, String name) {

    public static final String KIND_PACKAGE = "package";
    public static final String KIND_TYPE = "type";
    public static final String KIND_METHOD = "method";
    public static final String KIND_EXPORT = "export";
    public static final String KIND_FUNCTION = "function";
    public static final String KIND_COMPONENT = "component";

    /** repo map 中的展示文本；升级前的输出格式保持不变 */
    public String display() {
        return switch (kind) {
            case KIND_PACKAGE -> "package " + name;
            case KIND_METHOD -> name + "()";
            case KIND_COMPONENT -> "component " + name;
            default -> name;
        };
    }
}
