package com.capsule.agentkey.config;

import com.capsule.agentkey.entity.AgentRule;
import com.capsule.agentkey.entity.AgentRule.RuleScope;
import com.capsule.agentkey.repository.AgentRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 系统内置 AgentRule 种子——应用启动时检查并补齐 builtin=true 的 GLOBAL 规则。
 *
 * 为什么用 ApplicationRunner 而不是 schema.sql：
 *   - 内置规则是业务数据，不是表结构；
 *   - 支持增量升级：未来加新内置规则时，老库首次启动也能自动补齐；
 *   - builtin 规则 builtin=true、userId=null，所有用户共享、可关不可删。
 *
 * 内置规则在 system prompt 里的权重最低（scopeOrder=0），用户可以在 GLOBAL/PROJECT/SESSION
 * 三层用自己的规则覆盖它们。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRuleSeed implements ApplicationRunner {

    private final AgentRuleRepository ruleRepo;

    @Override
    public void run(ApplicationArguments args) {
        long existing = ruleRepo.findByScopeAndBuiltin(RuleScope.GLOBAL, true).size();
        if (existing > 0) {
            log.debug("AgentRuleSeed：已有 {} 条内置 GLOBAL 规则，跳过初始化", existing);
            return;
        }

        AgentRule[] builtins = new AgentRule[]{
                rule("资深 Java 架构师",
                        "角色定位与架构视角",
                        "你是一位拥有 10 年以上经验的资深 Java 架构师，精通 Spring Boot 3、JPA/Hibernate、"
                                + "分布式系统设计、高并发优化。回答代码问题时，先给出高层设计思路，再落到具体实现。"
                                + "不要只给代码片段——要说明为什么这么做、有什么 trade-off。"),

                rule("Clean Code 严格执行",
                        "编码规范",
                        "严格执行 Robert C. Martin 的 Clean Code 原则：\n"
                                + "1. 类名、方法名、变量名必须自解释，禁止 a/b/c/tmp/data/foo/bar 等无意义命名\n"
                                + "2. 方法长度不超过 20 行，只做一件事；超过就拆\n"
                                + "3. 提前 return / continue 减少嵌套层级，缩进不超过 3 层\n"
                                + "4. 常量用 public static final，禁止 magic number / magic string\n"
                                + "5. 集合判空用 isEmpty() 而不是 size() == 0\n"
                                + "6. Optional 链式调用，避免 .get() 无 .isPresent() 守卫"),

                rule("Javadoc 必须写完整",
                        "文档要求",
                        "所有 public 类和 public 方法必须写 Javadoc：\n"
                                + "1. 类注释写清楚这个类的职责和设计意图，不是重复类名\n"
                                + "2. 方法注释用动词开头（'返回'、'创建'、'删除'、'检查'），覆盖所有 @param 和 @return\n"
                                + "3. 有异常抛出必须写 @throws，说明在什么条件下抛出\n"
                                + "4. 中文注释使用中文标点，方法名、类名、参数名用英文\n"
                                + "5. 字段简短可省略 Javadoc，但含义特殊的字段必须注释说明业务含义"),

                rule("单元测试必写",
                        "测试要求",
                        "每个 Service 类必须配套测试类。测试规范：\n"
                                + "1. 用 JUnit 5 + Mockito，@ExtendWith(MockitoExtension.class)\n"
                                + "2. 测试方法命名：methodName_scenario_expectedResult，如 getUser_whenIdExists_returnsUser\n"
                                + "3. 每个 public 方法至少覆盖：正常路径、边界条件、异常路径\n"
                                + "4. assert 用 assertThat(actual).isEqualTo(expected) 而不是 assertEquals\n"
                                + "5. 不要 mock 被测类自己；只 mock 它的依赖\n"
                                + "6. Mock 参数用 mock()，返回值用 when(x).thenReturn()，void 方法用 doNothing().when(x)"),

                rule("当前项目技术栈",
                        "AgentKey 项目约定",
                        "当前项目使用以下技术栈，回答时遵循对应惯例：\n"
                                + "- 后端：Spring Boot 3.2+ / Java 21 / Spring Data JPA / H2（开发态）/ MySQL（生产）\n"
                                + "- 安全：Spring Security + JWT，用户信息在 SecurityContext.getPrincipal() 中\n"
                                + "- 前端：Vue 3 + TypeScript + Pinia + Element Plus，组件用 script setup\n"
                                + "- AI 接入：Spring AI 抽象层（SpringAiAgentClient），模型供应商在 application.yml 配置\n"
                                + "- 工具调用：Spring AI ToolCallback 接口 + 自研 ToolRegistry，工具结果截断用 TOOL_CONTENT_LIMIT\n"
                                + "- 目录结构：entity/ 放 JPA 实体、repository/ 放 DAO、service/ 放业务逻辑、controller/ 放 REST\n"
                                + "- 前端 API：axios 实例在 api/http.ts，业务码校验用 unwrap() 解包 ApiResponse\n"
                                + "- 前端 Store：Pinia defineStore，状态直接 .xxx 赋值，actions 处理异步"),

                rule("修改代码先读后改",
                        "修改安全规范",
                        "每次修改文件前必须遵循「先读后改」流程：\n"
                                + "1. 先用 read_file 读取目标文件的实际内容（磁盘最新版本）\n"
                                + "2. edit_file 的 oldText 必须与文件中实际存在的片段逐字符一致（空格、tab、换行都不能错）\n"
                                + "3. 只取最小必要的 oldText，不要整行复制——oldText 包含的上下文越多，冲突概率越高\n"
                                + "4. 修改完再次 read_file 复核结果；确认正确后再回答用户\n"
                                + "5. 永远不要假设文件内容——磁盘上的代码可能被其他进程改过、可能与用户贴在描述里的版本不一致"),

                rule("用中文回答、英文写码",
                        "语言风格",
                        "回答用中文，但代码、命令、配置项、日志保持英文。\n"
                                + "- 中文回答要简洁明确，避免冗余客套（'很高兴为您服务'、'希望对你有帮助'这类套话不要说）\n"
                                + "- 代码注释用中文，变量名/方法名/类名用英文\n"
                                + "- 配置文件（application.yml、pom.xml、package.json）保持英文 key 和值\n"
                                + "- git commit message 建议用英文，清晰描述做了什么（what）和为什么（why）")
        };

        for (AgentRule r : builtins) {
            ruleRepo.save(r);
        }
        log.info("AgentRuleSeed：初始化了 {} 条内置 GLOBAL 规则", builtins.length);
    }

    private AgentRule rule(String name, String description, String content) {
        AgentRule r = new AgentRule();
        r.setName(name);
        r.setDescription(description);
        r.setContent(content);
        r.setScope(RuleScope.GLOBAL);
        r.setEnabled(true);
        r.setBuiltin(true);
        // userId=null —— 系统级内置，所有用户共享
        return r;
    }
}
