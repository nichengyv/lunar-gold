//工具注册中心，自动发现所有工具
package com.capsule.agentkey.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心：支持运行时动态注册和发现
 */
@Component
@Slf4j
public class ToolRegistry {


    //存放工具的"抽屉柜"，key 是工具名，value 是工具实例
    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    // applicationContext是Spring 的"大仓库"，里面装着所有被 @Component 标记的 Bean
    private final ApplicationContext applicationContext;

    public ToolRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    //这是 Java 的一个"钩子注解"
    //含义：这个 Bean 创建完成、所有依赖注入完毕后，立刻执行这个方法，完成注册
    @PostConstruct//这个注解扫描所有spring容器以找到工具类
    public void init() {
        // 自动扫描 Spring 容器中所有 BaseTool 实现（basetool.class限定所有带 @Component 且 implements BaseTool 的类）
        //返回的是一个 Map<String, BaseTool>，key 是 Spring 给 Bean 起的名字（默认是类名首字母小写，如 calculatorTool），value 是实例对象
        //getBeansOfType通过参数找到继承或实现它的类
        Map<String, BaseTool> beans = applicationContext.getBeansOfType(BaseTool.class);
        //遍历扫描出的全部工具实例，逐个调用本类的register()方法完成注册
        beans.values().forEach(this::register);
        //打印日志（log：日志对象，info：日志级别）
        log.info("✅ Agent 工具注册完成，共 {} 个: {}", tools.size(), tools.keySet());
    }

    //这个方法用于工具注册
    public void register(BaseTool tool) {
        String name = tool.getName();//getName由接口定义，获取工具名而不是类名（因为deepseek大模型不认识）
        if (tools.containsKey(name)) {
            log.warn("工具 [{}] 已被覆盖", name);
        }
        //把工具按名称存入map
        tools.put(name, tool);
        log.info("🔧 注册工具: {} - {}", name, tool.getDescription());
    }

    public BaseTool getTool(String name) {
        return tools.get(name);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    public Collection<BaseTool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }


    //写文件类工具：只读权限下不暴露
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file");

    //执行命令工具：只有「可执行」权限才暴露
    private static final Set<String> EXECUTE_TOOLS = Set.of("run_command");

    /**
     * 权限过滤：模型只能看到当前工作权限允许的工具，
     * 避免它尝试越权调用、答非所问地反复报错。
     */
    private boolean isAllowed(String toolName, com.capsule.agentkey.service.AgentPolicy.Permission permission) {
        if (permission == null) {
            return true;
        }
        if (!permission.canWrite() && WRITE_TOOLS.contains(toolName)) {
            return false;
        }
        return permission.canExecute() || !EXECUTE_TOOLS.contains(toolName);
    }

    //这个方法告诉大模型有哪些工具可以用
    // 把工具翻译成"大模型能看懂的语言"
    // 生成 OpenAI/DeepSeek 格式的 function calling 定义
    public List<Map<String, Object>> buildToolDefinitions(com.capsule.agentkey.service.AgentPolicy.Permission permission) {
        List<Map<String, Object>> definitions = new ArrayList<>();//json最外层，工具清单
        for (BaseTool tool : tools.values()) {
            if (!isAllowed(tool.getName(), permission)) {
                continue;//越权工具不进清单
            }
            Map<String, Object> def = new HashMap<>();//单个工具的包装壳
            def.put("type", "function");//告诉模型，这是一个函数类型的工具
            Map<String, Object> function = new HashMap<>();//工具说明书
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", tool.getParametersSchema());
            def.put("function", function);//把具体函数定义塞进去
            definitions.add(def);
        }
        return definitions;
    }
//大模型决定调用后，真正执行工具，解析大模型传来的参数字符串，dispatch调用execute
// 大模型说："我要用 get_current_weather，参数是 {'city':'北京'}"
// 这里负责：找到工具 → 解析参数 → 执行 → 返回结果
    public ToolResult dispatch(String toolName, String argumentsJson,
                               com.capsule.agentkey.service.AgentPolicy.Permission permission) {
        //用map集合自带的get方法，根据工具名称，取出对应的工具实例
        BaseTool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.fail("未知工具: " + toolName);
        }
        //兜底：模型可能绕过工具清单直接指名调用，这里再拦一次
        if (!isAllowed(toolName, permission)) {
            String label = permission == null ? "受限" : permission.label();
            return ToolResult.fail("当前工作权限为「" + label + "」，不允许调用工具 " + toolName);
        }
        try {
            //创建jsckson序列化对象，把传入的参数字符串json转换为jsonNode树形对象
            ObjectMapper mapper = new ObjectMapper();
            //调用工具，执行它的execute方法，传入解析后的参数
            return tool.execute(mapper.readTree(argumentsJson));
        } catch (Exception e) {
            //出现的任何异常，打印错误日志，返回统一的失败结果
            log.error("工具 [{}] 执行异常", toolName, e);
            return ToolResult.fail("执行异常: " + e.getMessage());
        }
    }
}
