package com.capsule.agentkey.util;

/**
 * 轻量逐行 diff。
 *
 * 不做完整的 LCS：Agent 的 edit_file 通常只改一处，write_file 是整段覆盖，
 * 用「公共前缀 + 公共后缀」裁出中间变化块已经足够可读，也避免了 O(n*m) 的内存开销。
 * 中间块整体按「删除行 / 新增行」两段输出，前端按行首符号着色。
 */
public final class SimpleDiff {

    private SimpleDiff() {
    }

    public record Result(int additions, int deletions, String text) {
        public static Result empty() {
            return new Result(0, 0, "");
        }
    }

    /**
     * @param maxLines 单侧最多展示多少行，超出只计数并提示省略
     */
    public static Result compute(String oldText, String newText, int maxLines) {
        String[] a = lines(oldText);
        String[] b = lines(newText);

        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) {
            start++;
        }
        int endA = a.length;
        int endB = b.length;
        while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1])) {
            endA--;
            endB--;
        }

        int deletions = endA - start;
        int additions = endB - start;
        if (deletions == 0 && additions == 0) {
            return Result.empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("@@ -").append(start + 1).append(',').append(deletions)
                .append(" +").append(start + 1).append(',').append(additions).append(" @@\n");

        int shownDel = Math.min(deletions, maxLines);
        for (int i = 0; i < shownDel; i++) {
            sb.append("- ").append(a[start + i]).append('\n');
        }
        if (deletions > shownDel) {
            sb.append("…（省略 ").append(deletions - shownDel).append(" 行删除）\n");
        }

        int shownAdd = Math.min(additions, maxLines);
        for (int i = 0; i < shownAdd; i++) {
            sb.append("+ ").append(b[start + i]).append('\n');
        }
        if (additions > shownAdd) {
            sb.append("…（省略 ").append(additions - shownAdd).append(" 行新增）\n");
        }
        return new Result(additions, deletions, sb.toString());
    }

    private static String[] lines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String[] raw = text.split("\n", -1);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].endsWith("\r")) {
                raw[i] = raw[i].substring(0, raw[i].length() - 1);
            }
        }
        return raw;
    }
}
