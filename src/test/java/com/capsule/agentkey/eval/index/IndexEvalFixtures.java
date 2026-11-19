package com.capsule.agentkey.eval.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * P5-04 阶段 A 的确定性 fixture：合成工作区与符号标注集。
 *
 * <p>合成工作区不提交进仓库，运行时在临时目录生成 800 个文件（Java 500、TS 150、
 * Vue 50、文档 100），用于测量索引构建成本、增量复用与大仓库确定性。</p>
 */
final class IndexEvalFixtures {

    static final int JAVA_FILES = 500;
    static final int TS_FILES = 150;
    static final int VUE_FILES = 50;
    static final int DOC_FILES = 100;

    private IndexEvalFixtures() {
    }

    /** 生成 800 文件 / 深度 8 的合成工作区；内容固定，保证可重复。 */
    static Path createSyntheticWorkspace(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("synthetic-workspace"));
        for (int i = 0; i < JAVA_FILES; i++) {
            int pkg = i % 10;
            Path file = root.resolve("src/main/java/com/demo/p" + pkg + "/service/Type" + i + ".java");
            write(file, javaSource(pkg, i));
        }
        for (int i = 0; i < TS_FILES; i++) {
            Path file = root.resolve("frontend/src/features/f" + (i % 15) + "/helpers" + i + ".ts");
            write(file, "export function helper" + i + "(value: number) {\n  return value;\n}\n");
        }
        for (int i = 0; i < VUE_FILES; i++) {
            Path file = root.resolve("frontend/src/components/Comp" + i + ".vue");
            write(file, "<template><div>component " + i + "</div></template>\n"
                    + "<script setup lang=\"ts\">\nconst value = " + i + ";\n</script>\n");
        }
        for (int i = 0; i < DOC_FILES; i++) {
            write(root.resolve("docs/guide/g" + i + ".md"), "# Guide " + i + "\n");
        }
        return root;
    }

    private static String javaSource(int pkg, int index) {
        return "package com.demo.p" + pkg + ".service;\n"
                + "\n"
                + "public class Type" + index + " {\n"
                + "    public void run" + index + "() {\n"
                + "    }\n"
                + "}\n";
    }

    /**
     * 符号标注集：文件相对路径 -> 期望的符号名。
     *
     * <p>期望集合按 {@code LightweightCodeIndexer} 的契约标注：Java 包 / 类型 / 方法，
     * 其余语言顶层符号。C# 故意保留，用于记录当前索引器不支持的扩展名。</p>
     */
    static Map<String, ExpectedSymbols> labeledSymbolFiles() {
        Map<String, ExpectedSymbols> files = new LinkedHashMap<>();

        files.put("java/OrderService.java", new ExpectedSymbols("java", List.of(
                "com.demo.order", "OrderService", "createOrder", "total"), """
                package com.demo.order;

                import java.util.List;

                public class OrderService {
                    private final List<String> items = List.of();

                    public void createOrder() {
                    }

                    private int total() {
                        return 0;
                    }
                }
                """));
        files.put("java/RefundPolicy.java", new ExpectedSymbols("java", List.of(
                "com.demo.order", "RefundPolicy", "of"), """
                package com.demo.order;

                public record RefundPolicy(String reason) {
                    public static RefundPolicy of(String reason) {
                        return new RefundPolicy(reason);
                    }
                }
                """));
        files.put("java/InventoryClient.java", new ExpectedSymbols("java", List.of(
                "com.demo.inventory", "InventoryClient", "available"), """
                package com.demo.inventory;

                public interface InventoryClient {
                    int available(String sku);
                }
                """));
        files.put("java/OrderController.java", new ExpectedSymbols("java", List.of(
                "com.demo.web", "OrderController", "Inner", "handle"), """
                package com.demo.web;

                public class OrderController {
                    public static class Inner {
                    }

                    public void handle() {
                    }
                }
                """));

        files.put("ts/orderApi.ts", new ExpectedSymbols("ts", List.of(
                "Order", "fetchOrders", "OrderClient"), """
                export interface Order {
                  id: string;
                }

                export const fetchOrders = async (): Promise<Order[]> => [];

                export default class OrderClient {}
                """));
        files.put("ts/orderStore.ts", new ExpectedSymbols("ts", List.of(
                "OrderState", "createStore"), """
                export type OrderState = { count: number };

                export function createStore() {
                  return {};
                }
                """));

        files.put("vue/OrderBadge.vue", new ExpectedSymbols("vue", List.of("OrderBadge"), """
                <template><span>badge</span></template>
                <script setup lang="ts">
                const label = 'order';
                </script>
                """));

        files.put("python/refund_tools.py", new ExpectedSymbols("python", List.of(
                "RefundService", "calculate_fee"), """
                class RefundService:
                    def refund(self, order_id):
                        return order_id


                def calculate_fee(amount):
                    return amount
                """));

        files.put("go/order_service.go", new ExpectedSymbols("go", List.of(
                "Order", "NewOrder", "Cancel"), """
                package order

                type Order struct {
                \tID string
                }

                func NewOrder(id string) *Order {
                \treturn &Order{ID: id}
                }

                func (s *Service) Cancel(id string) error {
                \treturn nil
                }
                """));
        files.put("go/fee.go", new ExpectedSymbols("go", List.of(
                "Fee", "CalculateFee"), """
                package order

                type Fee struct {
                \tAmount int
                }

                func CalculateFee(amount int) int {
                \treturn amount
                }
                """));

        files.put("rust/refund.rs", new ExpectedSymbols("rust", List.of(
                "Refund", "calculate"), """
                pub struct Refund {
                    pub amount: i64,
                }

                impl Refund {
                    pub fn new(amount: i64) -> Self {
                        Self { amount }
                    }
                }

                pub fn calculate(amount: i64) -> i64 {
                    amount
                }
                """));

        files.put("csharp/OrderRepository.cs", new ExpectedSymbols("csharp", List.of(
                "OrderRepository", "FindAsync"), """
                namespace Demo;

                public class OrderRepository
                {
                    public Task<Order> FindAsync(string id)
                    {
                        return null;
                    }
                }
                """));
        return files;
    }

    static Path writeLabeledFiles(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("symbol-fixture"));
        for (Map.Entry<String, ExpectedSymbols> entry : labeledSymbolFiles().entrySet()) {
            write(root.resolve(entry.getKey()), entry.getValue().content());
        }
        return root;
    }

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    record ExpectedSymbols(String language, List<String> names, String content) {
    }
}
