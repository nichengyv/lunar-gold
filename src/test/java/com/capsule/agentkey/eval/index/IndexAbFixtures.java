package com.capsule.agentkey.eval.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5-04 阶段 B 的 navigation fixture 与用例集。
 *
 * <p>fixture 运行时生成，约 36 个文件、6 个 Java 包 + 前端 / 脚本 / 文档，
 * 刻意包含 {@code OrderHelper}、{@code RefundPolicyLegacy}、
 * {@code cancelOrderRemote} 等近名干扰，避免“搜关键词就能唯一命中”。</p>
 */
final class IndexAbFixtures {

    private IndexAbFixtures() {
    }

    static Path create(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("navigation-project"));
        for (Map.Entry<String, String> entry : files().entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }

    static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();

        files.put("src/main/java/com/shop/order/OrderService.java", """
                package com.shop.order;

                public class OrderService {
                    private final OrderRepository repository;

                    public OrderService(OrderRepository repository) {
                        this.repository = repository;
                    }

                    public void createOrder(String orderId) {
                        repository.save(new Order(orderId, OrderStatus.CREATED));
                    }

                    public void cancelOrder(String orderId) {
                        Order order = repository.findById(orderId);
                        order.setStatus(OrderStatus.CANCELLED);
                        order.markCancelledAt();
                        repository.save(order);
                    }
                }
                """);
        files.put("src/main/java/com/shop/order/OrderHelper.java", """
                package com.shop.order;

                public final class OrderHelper {
                    private OrderHelper() {
                    }

                    public static String normalizeStatus(String raw) {
                        return raw == null ? "" : raw.trim().toUpperCase();
                    }
                }
                """);
        files.put("src/main/java/com/shop/order/OrderRepository.java", """
                package com.shop.order;

                import java.util.HashMap;
                import java.util.Map;

                public class OrderRepository {
                    private final Map<String, Order> store = new HashMap<>();

                    public Order findById(String orderId) {
                        return store.get(orderId);
                    }

                    public void save(Order order) {
                        store.put(order.getId(), order);
                    }
                }
                """);
        files.put("src/main/java/com/shop/order/Order.java", """
                package com.shop.order;

                import java.time.Instant;

                public class Order {
                    private final String id;
                    private OrderStatus status;
                    private Instant cancelledAt;

                    public Order(String id, OrderStatus status) {
                        this.id = id;
                        this.status = status;
                    }

                    public String getId() {
                        return id;
                    }

                    public void setStatus(OrderStatus status) {
                        this.status = status;
                    }

                    public void markCancelledAt() {
                        this.cancelledAt = Instant.now();
                    }
                }
                """);
        files.put("src/main/java/com/shop/order/OrderStatus.java", """
                package com.shop.order;

                public enum OrderStatus {
                    CREATED,
                    PAID,
                    CANCELLED,
                    CLOSED
                }
                """);

        files.put("src/main/java/com/shop/refund/RefundService.java", """
                package com.shop.refund;

                public class RefundService {
                    private final RefundPolicy policy = new RefundPolicy();

                    public double requestRefund(double amount) {
                        return policy.calculateRefundFee(amount);
                    }

                    public boolean approveRefund(String refundId) {
                        return refundId != null && !refundId.isBlank();
                    }
                }
                """);
        files.put("src/main/java/com/shop/refund/RefundPolicy.java", """
                package com.shop.refund;

                public class RefundPolicy {

                    public double calculateRefundFee(double amount) {
                        if (amount <= 0) {
                            return 0;
                        }
                        return amount * 0.9;
                    }
                }
                """);
        files.put("src/main/java/com/shop/refund/RefundPolicyLegacy.java", """
                package com.shop.refund;

                /** 旧版退款策略，仅保留兼容，不再新增调用方。 */
                public class RefundPolicyLegacy {

                    public double calculateRefundFeeLegacy(double amount) {
                        return amount * 0.8;
                    }
                }
                """);

        files.put("src/main/java/com/shop/inventory/InventoryService.java", """
                package com.shop.inventory;

                public class InventoryService {
                    private static final int DEFAULT_RESERVE = 1;

                    public void reserveStock(String sku, int quantity) {
                        int reserved = quantity <= 0 ? DEFAULT_RESERVE : quantity;
                        record(sku, reserved);
                    }

                    public void releaseStock(String sku, int quantity) {
                        record(sku, -quantity);
                    }

                    private void record(String sku, int delta) {
                    }
                }
                """);
        files.put("src/main/java/com/shop/inventory/StockSnapshot.java", """
                package com.shop.inventory;

                public record StockSnapshot(String sku, int available) {
                }
                """);

        files.put("src/main/java/com/shop/payment/PaymentGateway.java", """
                package com.shop.payment;

                public class PaymentGateway {

                    public boolean charge(String orderId, long amount) {
                        if (orderId == null || amount <= 0) {
                            return false;
                        }
                        return submit(orderId, amount);
                    }

                    public boolean refundPayment(String paymentId) {
                        return paymentId != null;
                    }

                    private boolean submit(String orderId, long amount) {
                        return true;
                    }
                }
                """);
        files.put("src/main/java/com/shop/payment/PaymentRetryPolicy.java", """
                package com.shop.payment;

                public class PaymentRetryPolicy {

                    public long nextDelay(int attempt) {
                        return (long) Math.pow(2, attempt) * 100L;
                    }
                }
                """);
        files.put("src/main/java/com/shop/payment/PaymentRecord.java", """
                package com.shop.payment;

                public record PaymentRecord(String orderId, long amount, boolean success) {
                }
                """);

        files.put("src/main/java/com/shop/coupon/CouponService.java", """
                package com.shop.coupon;

                public class CouponService {
                    private final CouponRuleEngine engine = new CouponRuleEngine();

                    public boolean validateCoupon(String code, double amount) {
                        return engine.evaluate(code, amount);
                    }

                    public double applyCoupon(String code, double amount) {
                        return validateCoupon(code, amount) ? amount - 5 : amount;
                    }
                }
                """);
        files.put("src/main/java/com/shop/coupon/CouponRuleEngine.java", """
                package com.shop.coupon;

                public class CouponRuleEngine {

                    public boolean evaluate(String code, double amount) {
                        return code != null && !code.isBlank() && amount > 0;
                    }
                }
                """);

        files.put("src/main/java/com/shop/notification/NotificationService.java", """
                package com.shop.notification;

                public class NotificationService {
                    private final EmailTemplate template = new EmailTemplate();

                    public void sendOrderCreated(String orderId) {
                        String body = template.render("order-created", orderId);
                        deliver(body);
                    }

                    private void deliver(String body) {
                    }
                }
                """);
        files.put("src/main/java/com/shop/notification/EmailTemplate.java", """
                package com.shop.notification;

                public class EmailTemplate {

                    public String render(String name, String orderId) {
                        return name + ":" + orderId;
                    }
                }
                """);

        files.put("src/main/java/com/shop/report/SalesReportService.java", """
                package com.shop.report;

                public class SalesReportService {
                    private final CsvExporter exporter = new CsvExporter();

                    public String buildDailyReport(String date) {
                        return exporter.export(date);
                    }
                }
                """);
        files.put("src/main/java/com/shop/report/CsvExporter.java", """
                package com.shop.report;

                public class CsvExporter {

                    public String export(String date) {
                        return "date,total\\n" + date + ",0";
                    }
                }
                """);

        files.put("src/main/java/com/shop/config/AppConfig.java", """
                package com.shop.config;

                public final class AppConfig {
                    public static final String APP_NAME = "shop";
                    public static final int PAGE_SIZE = 20;
                }
                """);
        files.put("src/main/java/com/shop/config/FeatureFlags.java", """
                package com.shop.config;

                public class FeatureFlags {

                    public boolean isEnabled(String flag) {
                        return "checkout-v2".equals(flag);
                    }
                }
                """);

        files.put("src/test/java/com/shop/order/OrderServiceTest.java", """
                package com.shop.order;

                class OrderServiceTest {

                    void cancelMarksCancelled() {
                    }
                }
                """);
        files.put("src/test/java/com/shop/refund/RefundServiceTest.java", """
                package com.shop.refund;

                class RefundServiceTest {

                    void refundUsesPolicy() {
                    }
                }
                """);

        files.put("frontend/src/api/orderApi.ts", """
                export interface OrderDto {
                  id: string;
                  status: string;
                }

                export async function fetchOrder(id: string): Promise<OrderDto> {
                  return request(`/orders/${id}`);
                }

                export async function cancelOrderRemote(id: string): Promise<void> {
                  await request(`/orders/${id}/cancel`, { method: 'POST' });
                }

                async function request(url: string, init?: RequestInit): Promise<OrderDto> {
                  return { id: url, status: 'CREATED' };
                }
                """);
        files.put("frontend/src/api/refundApi.ts", """
                export async function submitRefund(orderId: string): Promise<void> {
                  await fetch(`/refunds/${orderId}`, { method: 'POST' });
                }
                """);
        files.put("frontend/src/api/paymentApi.ts", """
                export async function payOrder(orderId: string): Promise<boolean> {
                  const response = await fetch(`/payments/${orderId}`);
                  return response.ok;
                }
                """);

        files.put("frontend/src/stores/orderStore.ts", """
                import { defineStore } from 'pinia';

                export const useOrderStore = defineStore('order', {
                  state: () => ({ orders: [] as string[] }),
                  actions: {
                    add(orderId: string) {
                      this.orders.push(orderId);
                    },
                  },
                });
                """);
        files.put("frontend/src/stores/cartStore.ts", """
                import { defineStore } from 'pinia';

                export const useCartStore = defineStore('cart', {
                  state: () => ({ items: [] as string[] }),
                });
                """);

        files.put("frontend/src/components/OrderCard.vue", """
                <template>
                  <div class="order-card">{{ orderId }}</div>
                </template>

                <script setup lang="ts">
                defineProps<{ orderId: string }>();
                </script>
                """);
        files.put("frontend/src/components/RefundDialog.vue", """
                <template>
                  <dialog>refund</dialog>
                </template>

                <script setup lang="ts">
                const visible = false;
                </script>
                """);

        files.put("frontend/src/utils/format.ts", """
                export function formatCurrency(value: number): string {
                  return '¥' + value.toFixed(2);
                }
                """);
        files.put("frontend/src/utils/date.ts", """
                export function formatDate(value: Date): string {
                  return value.toISOString().slice(0, 10);
                }
                """);

        files.put("frontend/src/views/OrderDetail.vue", """
                <template>
                  <OrderCard :order-id="orderId" />
                </template>

                <script setup lang="ts">
                import OrderCard from '../components/OrderCard.vue';
                const orderId = 'demo';
                </script>
                """);

        files.put("scripts/deploy.ps1", "Write-Output 'deploy'\n");
        files.put("scripts/backup.ps1", "Write-Output 'backup'\n");
        files.put("docs/architecture.md", "# Architecture\n\norder / refund / inventory / payment\n");
        files.put("README.md", "# Navigation Fixture\n\nP5-04 A/B evaluation workspace.\n");
        return files;
    }

    /** 16 条用例：12 条定位 + 4 条修改；prompt 不直接给出目标文件或符号名。 */
    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();

        cases.add(Case.locate("locate-cancel-order",
                "用户取消订单时会走哪段代码？只回答实现该逻辑的文件相对路径，不要解释。",
                "src/main/java/com/shop/order/OrderService.java", "cancelOrder"));
        cases.add(Case.locate("locate-refund-fee",
                "退款时手续费是怎么算的？只回答实现该计算的文件相对路径，不要解释。",
                "src/main/java/com/shop/refund/RefundPolicy.java", "calculateRefundFee"));
        cases.add(Case.locate("locate-stock-reserve",
                "商品出库前的库存预占逻辑在哪个文件？只回答相对路径，不要解释。",
                "src/main/java/com/shop/inventory/InventoryService.java", "reserveStock"));
        cases.add(Case.locate("locate-payment-charge",
                "真正发起支付扣款的代码在哪个文件？只回答相对路径，不要解释。",
                "src/main/java/com/shop/payment/PaymentGateway.java", "charge"));
        cases.add(Case.locate("locate-coupon-validate",
                "优惠券是否可用的校验逻辑在哪个文件？只回答相对路径，不要解释。",
                "src/main/java/com/shop/coupon/CouponService.java", "validateCoupon"));
        cases.add(Case.locate("locate-order-created-notify",
                "订单创建成功后发送通知的代码在哪个文件？只回答相对路径，不要解释。",
                "src/main/java/com/shop/notification/NotificationService.java", "sendOrderCreated"));
        cases.add(Case.locate("locate-daily-report",
                "每日销售报表生成在哪个文件？只回答相对路径，不要解释。",
                "src/main/java/com/shop/report/SalesReportService.java", "buildDailyReport"));
        cases.add(Case.locate("locate-frontend-cancel",
                "前端提交取消订单请求的代码在哪个文件？只回答相对路径，不要解释。",
                "frontend/src/api/orderApi.ts", "cancelOrderRemote"));
        cases.add(Case.locate("locate-frontend-order-store",
                "前端管理订单状态的 store 在哪个文件？只回答相对路径，不要解释。",
                "frontend/src/stores/orderStore.ts", "useOrderStore"));
        cases.add(Case.locate("locate-currency-format",
                "前端金额格式化工具在哪个文件？只回答相对路径，不要解释。",
                "frontend/src/utils/format.ts", "formatCurrency"));
        cases.add(Case.locate("locate-payment-retry",
                "支付失败后的重试间隔是怎么算的？只回答实现该逻辑的文件相对路径，不要解释。",
                "src/main/java/com/shop/payment/PaymentRetryPolicy.java", "nextDelay"));
        cases.add(Case.locate("locate-email-render",
                "通知邮件模板的渲染逻辑在哪个文件？只回答相对路径，不要解释。",
                "src/main/java/com/shop/notification/EmailTemplate.java", "render"));

        cases.add(Case.edit("edit-cancel-status",
                "用户取消订单时，请把订单状态改为 CLOSED，并保留现有的取消时间记录。只修改必要代码，完成后简要说明。",
                "src/main/java/com/shop/order/OrderService.java", "CLOSED", "CANCELLED"));
        cases.add(Case.edit("edit-refund-rate",
                "退款手续费需要按订单金额的 0.85 计算。只修改必要代码，完成后简要说明。",
                "src/main/java/com/shop/refund/RefundPolicy.java", "0.85", "0.9"));
        cases.add(Case.edit("edit-stock-default",
                "商品出库前预占库存时，未指定数量应默认预占 2 件。只修改必要代码，完成后简要说明。",
                "src/main/java/com/shop/inventory/InventoryService.java",
                "DEFAULT_RESERVE=2", "DEFAULT_RESERVE=1"));
        cases.add(Case.edit("edit-currency-symbol",
                "前端金额展示统一使用 CNY 前缀，不再使用 ¥ 符号。只修改必要代码，完成后简要说明。",
                "frontend/src/utils/format.ts", "CNY", "¥"));
        return cases;
    }

    record Case(String id, String category, String prompt, String targetPath,
                String keyword, String expectContains, String expectAbsent) {

        static Case locate(String id, String prompt, String targetPath, String keyword) {
            return new Case(id, "locate", prompt, targetPath, keyword, null, null);
        }

        static Case edit(String id, String prompt, String targetPath,
                         String expectContains, String expectAbsent) {
            return new Case(id, "edit", prompt, targetPath, null, expectContains, expectAbsent);
        }
    }
}
