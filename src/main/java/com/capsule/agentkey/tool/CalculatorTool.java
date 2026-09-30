package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class CalculatorTool implements BaseTool {

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getDescription() {
        return "精确计算器。当用户涉及数学运算、单位换算、财务计算、日期差值时，" +
                "使用此工具代替心算，确保结果100%准确。支持 + - * / ^ % 及括号。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        Map<String, Object> expr = new HashMap<>();
        expr.put("type", "string");
        expr.put("description", "数学表达式，如 '123 * 456', '(100 + 200) / 3', '2^10', '17 % 5'");
        props.put("expression", expr);
        schema.put("properties", props);
        schema.put("required", new String[]{"expression"});
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        String expr = arguments.path("expression").asText();
        log.info("计算器收到的表达式：{}", expr);

        if (expr == null || expr.trim().isEmpty()) {
            return ToolResult.fail("表达式不能为空");
        }

        try {
            double result = evaluate(expr);
            // 整数结果显示为整数形式，避免出现 4.0 这种
            String display;
            if (!Double.isInfinite(result) && !Double.isNaN(result)
                    && result == Math.rint(result)) {
                display = String.valueOf((long) result);
            } else {
                display = String.valueOf(result);
            }
            return ToolResult.ok("计算结果: " + display + "\n表达式: " + expr);
        } catch (ArithmeticException e) {
            return ToolResult.fail("计算错误: " + e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("表达式无法解析: " + e.getMessage());
        }
    }

    // 运算符优先级表
    private static final Map<Character, Integer> PRECEDENCE = new HashMap<>();
    static {
        PRECEDENCE.put('+', 1);
        PRECEDENCE.put('-', 1);
        PRECEDENCE.put('*', 2);
        PRECEDENCE.put('/', 2);
        PRECEDENCE.put('%', 2);
        PRECEDENCE.put('^', 3);
        PRECEDENCE.put('~', 4); // 一元负号，最高优先级
    }

    /**
     * 双栈算法（Dijkstra Shunting Yard 思路）直接对中缀表达式求值。
     * 纯 Java 实现，不依赖任何脚本引擎，从根上避免代码注入风险。
     */
    private double evaluate(String expr) {
        // 去除所有空白字符
        String s = expr.replaceAll("\\s", "");
        if (s.isEmpty()) {
            throw new IllegalArgumentException("表达式不能为空");
        }

        // 安全校验：只允许数字、小数点、运算符和括号
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c) && c != '.' && !PRECEDENCE.containsKey(c)
                    && c != '(' && c != ')') {
                throw new IllegalArgumentException("包含非法字符: " + c);
            }
        }

        Deque<Double> values = new ArrayDeque<>();
        Deque<Character> ops = new ArrayDeque<>();
        int i = 0;
        int n = s.length();
        // 标记当前是否期望一个操作数，用于识别一元正负号
        boolean expectOperand = true;

        while (i < n) {
            char c = s.charAt(i);

            // 数字字面量
            if (Character.isDigit(c) || c == '.') {
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                String numStr = s.substring(start, i);
                if (numStr.chars().filter(ch -> ch == '.').count() > 1) {
                    throw new IllegalArgumentException("非法数字: " + numStr);
                }
                values.push(Double.parseDouble(numStr));
                expectOperand = false;
                continue;
            }

            // 左括号
            if (c == '(') {
                ops.push(c);
                expectOperand = true;
                i++;
                continue;
            }

            // 右括号：不断出栈计算，直到遇到匹配的左括号
            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') {
                    applyOp(values, ops.pop());
                }
                if (ops.isEmpty() || ops.peek() != '(') {
                    throw new IllegalArgumentException("括号不匹配");
                }
                ops.pop(); // 弹出匹配的左括号
                expectOperand = false;
                i++;
                continue;
            }

            // 运算符
            // 出现在表达式开头、左括号之后、或其它运算符之后的 +/- 视为一元正负号
            if ((c == '-' || c == '+') && expectOperand) {
                if (c == '-') {
                    ops.push('~'); // 用 ~ 标记一元负号
                }
                // 一元正号直接忽略
                i++;
                continue;
            }

            // 二元运算符：先按优先级和结合性出栈计算，再压入当前运算符
            while (!ops.isEmpty() && ops.peek() != '(' && ops.peek() != ')') {
                char top = ops.peek();
                if (top == '~') {
                    // 一元运算符优先级最高，直接先算
                    ops.pop();
                    applyOp(values, '~');
                    continue;
                }
                int topPrec = PRECEDENCE.get(top);
                int curPrec = PRECEDENCE.get(c);
                // ^ 右结合（2^3^2 = 2^(3^2)），其余左结合
                boolean shouldCompute = (c == '^') ? (topPrec > curPrec) : (topPrec >= curPrec);
                if (shouldCompute) {
                    ops.pop();
                    applyOp(values, top);
                } else {
                    break;
                }
            }
            ops.push(c);
            expectOperand = true;
            i++;
        }

        // 处理栈中剩余运算符
        while (!ops.isEmpty()) {
            char op = ops.pop();
            if (op == '(' || op == ')') {
                throw new IllegalArgumentException("括号不匹配");
            }
            applyOp(values, op);
        }

        if (values.size() != 1) {
            throw new IllegalArgumentException("表达式不完整或格式错误");
        }
        return values.pop();
    }

    /**
     * 弹出操作数并应用运算符
     */
    private void applyOp(Deque<Double> values, char op) {
        if (op == '~') {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("一元负号缺少操作数");
            }
            values.push(-values.pop());
            return;
        }
        if (values.size() < 2) {
            throw new IllegalArgumentException("运算符 '" + op + "' 缺少操作数");
        }
        double b = values.pop();
        double a = values.pop();
        double r;
        switch (op) {
            case '+': r = a + b; break;
            case '-': r = a - b; break;
            case '*': r = a * b; break;
            case '/':
                if (b == 0) throw new ArithmeticException("除零错误");
                r = a / b;
                break;
            case '%':
                if (b == 0) throw new ArithmeticException("对零取模");
                r = a % b;
                break;
            case '^':
                r = Math.pow(a, b);
                break;
            default:
                throw new IllegalArgumentException("未知运算符: " + op);
        }
        values.push(r);
    }
}
