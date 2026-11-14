package com.capsule.agentkey.service.index;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量正则索引器：零额外依赖，覆盖 Java 与常见脚本语言的顶层符号。
 *
 * <p>只做“够用”的符号摘要，不追求语法树级准确率；误报一个符号的代价远小于
 * 引入原生解析器带来的打包风险。</p>
 */
@Component
public class LightweightCodeIndexer implements CodeIndexer {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "py", "js", "jsx", "ts", "tsx", "vue",
            "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "php", "rb", "swift");

    private static final Pattern JAVA_PACKAGE =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?(?:final\\s+)?"
                    + "(?:abstract\\s+)?(?:sealed\\s+)?(?:class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s{2,}(?:public|protected|private)\\s+(?:static\\s+)?(?:final\\s+)?(?:synchronized\\s+)?"
                    + "[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\(",
            Pattern.MULTILINE);
    private static final Pattern JS_EXPORT = Pattern.compile(
            "^\\s*export\\s+(?:default\\s+)?(?:abstract\\s+)?"
                    + "(?:class|interface|type|enum|function|const|let)\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern PY_SYMBOL =
            Pattern.compile("^(?:class|def)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern GO_SYMBOL =
            Pattern.compile("^(?:type|func)\\s+(?:\\([^)]*\\)\\s*)?(\\w+)", Pattern.MULTILINE);
    private static final Pattern RUST_SYMBOL =
            Pattern.compile("^(?:pub\\s+)?(?:fn|struct|enum|trait|impl)\\s+(\\w+)", Pattern.MULTILINE);

    @Override
    public boolean supports(String extension) {
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    @Override
    public List<CodeSymbol> index(String fileName, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String ext = extension(fileName);
        if (!supports(ext)) {
            return List.of();
        }
        return switch (ext) {
            case "java" -> javaSymbols(content);
            case "js", "jsx", "ts", "tsx", "vue" -> jsSymbols(content, fileName, ext);
            case "py" -> functionSymbols(PY_SYMBOL, content);
            case "go" -> functionSymbols(GO_SYMBOL, content);
            case "rs" -> functionSymbols(RUST_SYMBOL, content);
            default -> List.of();
        };
    }

    private static List<CodeSymbol> javaSymbols(String content) {
        List<CodeSymbol> symbols = new ArrayList<>();
        Matcher pkg = JAVA_PACKAGE.matcher(content);
        if (pkg.find()) {
            symbols.add(new CodeSymbol(CodeSymbol.KIND_PACKAGE, pkg.group(1)));
        }
        for (String type : collect(JAVA_TYPE, content)) {
            symbols.add(new CodeSymbol(CodeSymbol.KIND_TYPE, type));
        }
        for (String method : collect(JAVA_METHOD, content)) {
            symbols.add(new CodeSymbol(CodeSymbol.KIND_METHOD, method));
        }
        return symbols;
    }

    private static List<CodeSymbol> jsSymbols(String content, String fileName, String ext) {
        List<CodeSymbol> symbols = new ArrayList<>();
        for (String name : collect(JS_EXPORT, content)) {
            symbols.add(new CodeSymbol(CodeSymbol.KIND_EXPORT, name));
        }
        if (symbols.isEmpty() && "vue".equals(ext)) {
            // Vue SFC 通常没有 export 语句，用文件名标识组件
            String base = fileName.substring(0, fileName.length() - ".vue".length());
            symbols.add(new CodeSymbol(CodeSymbol.KIND_COMPONENT, base));
        }
        return symbols;
    }

    private static List<CodeSymbol> functionSymbols(Pattern pattern, String content) {
        List<CodeSymbol> symbols = new ArrayList<>();
        for (String name : collect(pattern, content)) {
            symbols.add(new CodeSymbol(CodeSymbol.KIND_FUNCTION, name));
        }
        return symbols;
    }

    private static List<String> collect(Pattern pattern, String content) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
