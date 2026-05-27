package com.deepseek.demo.service;

import com.deepseek.demo.annotation.ActionType;
import com.deepseek.demo.annotation.Tool;
import com.deepseek.demo.annotation.ToolParam;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 的集成测试。
 * 使用 @SpringBootTest 加载完整的 Spring 上下文，
 * 通过 @TestConfiguration 注册测试工具 Bean，
 * 验证注解扫描、JSON Schema 生成、白名单校验和工具执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ToolRegistryTest {

    /**
     * 测试工具服务，提供被 @Tool 标注的方法用于注册和测试。
     */
    public static class TestToolService {

        @Tool(
                name = "test_greet",
                description = "向指定用户发送问候",
                parameters = {
                        @ToolParam(name = "name", type = "string", description = "用户名", required = true),
                        @ToolParam(name = "greeting", type = "string", description = "问候语")
                },
                action = ActionType.WRITE
        )
        public String greet(String name, String greeting) {
            if (greeting == null || greeting.isEmpty()) {
                return "Hello, " + name + "!";
            }
            return greeting + ", " + name + "!";
        }

        @Tool(
                name = "test_get_time",
                description = "获取当前时间（模拟）",
                action = ActionType.READ
        )
        public String getTime() {
            return "2026-05-13 12:00:00";
        }
    }

    /**
     * 测试配置，注册 TestToolService 和自定义白名单的 ToolRegistry。
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        public TestToolService testToolService() {
            return new TestToolService();
        }
    }

    @Autowired
    private ToolRegistry toolRegistry;

    // ==================== 注解扫描 ====================

    @Test
    void shouldScanToolsWithAnnotation() {
        // 验证扫描到的工具
        ToolMeta greetMeta = toolRegistry.getTool("test_greet");
        assertNotNull(greetMeta);
        assertEquals("test_greet", greetMeta.getName());
        assertEquals("向指定用户发送问候", greetMeta.getDescription());
        assertEquals(ActionType.WRITE, greetMeta.getAction());

        ToolMeta timeMeta = toolRegistry.getTool("test_get_time");
        assertNotNull(timeMeta);
        assertEquals("test_get_time", timeMeta.getName());
        assertEquals("获取当前时间（模拟）", timeMeta.getDescription());
        assertEquals(ActionType.READ, timeMeta.getAction());
    }

    @Test
    void getTool_ShouldThrowException_WhenToolNotFound() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> toolRegistry.getTool("non_existent_tool"));
        assertTrue(exception.getMessage().contains("non_existent_tool"));
    }

    // ==================== 参数扫描 ====================

    @Test
    void shouldScanParametersCorrectly() {
        ToolMeta meta = toolRegistry.getTool("test_greet");

        List<Map<String, Object>> params = meta.getParameters();
        assertEquals(2, params.size());

        // 第一个参数：name
        assertEquals("name", params.get(0).get("name"));
        assertEquals("string", params.get(0).get("type"));
        assertEquals("用户名", params.get(0).get("description"));

        // 第二个参数：greeting
        assertEquals("greeting", params.get(1).get("name"));
        assertEquals("string", params.get(1).get("type"));
        assertEquals("问候语", params.get(1).get("description"));

        // 必需参数
        assertEquals(1, meta.getRequiredParams().size());
        assertEquals("name", meta.getRequiredParams().get(0));
    }

    @Test
    void shouldHandleToolWithNoParameters() {
        ToolMeta meta = toolRegistry.getTool("test_get_time");

        assertNotNull(meta);
        assertTrue(meta.getParameters().isEmpty());
        assertTrue(meta.getRequiredParams().isEmpty());
    }

    // ==================== JSON Schema 生成 ====================

    @Test
    void toJsonSchema_ShouldReturnCorrectFormat() {
        List<Map<String, Object>> schemas = toolRegistry.toJsonSchema();

        // 应该有 12 个工具 schema（2 个测试工具 + query_task + create_task + feishu_send_message + 6 个金融工具 + delegate_task）
        assertEquals(12, schemas.size());

        for (Map<String, Object> schema : schemas) {
            // 最外层应有 type 和 function
            assertEquals("function", schema.get("type"));
            assertTrue(schema.containsKey("function"));

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) schema.get("function");
            assertTrue(function.containsKey("name"));
            assertTrue(function.containsKey("description"));
            assertTrue(function.containsKey("parameters"));

            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
            assertEquals("object", parameters.get("type"));
            assertTrue(parameters.containsKey("properties"));
        }
    }

    @Test
    void toJsonSchema_ShouldIncludePropertiesAndRequired() {
        List<Map<String, Object>> schemas = toolRegistry.toJsonSchema();

        // 找到 test_greet 的 schema
        Map<String, Object> greetSchema = findSchemaByName(schemas, "test_greet");
        assertNotNull(greetSchema);

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) greetSchema.get("function");

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");

        // 验证 properties
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertEquals(2, properties.size());
        assertTrue(properties.containsKey("name"));
        assertTrue(properties.containsKey("greeting"));

        // 验证 required
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parameters.get("required");
        assertEquals(1, required.size());
        assertEquals("name", required.get(0));
    }

    @Test
    void toJsonSchema_ShouldHandleNoParameterTool() {
        List<Map<String, Object>> schemas = toolRegistry.toJsonSchema();

        Map<String, Object> timeSchema = findSchemaByName(schemas, "test_get_time");
        assertNotNull(timeSchema);

        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) timeSchema.get("function");

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        assertTrue(properties.isEmpty());

        // 无必需参数时，required 字段不应该出现
        assertFalse(parameters.containsKey("required"));
    }

    // ==================== LangChain4j ToolSpecification 生成 ====================

    @Test
    void toToolSpecifications_ShouldReturnCorrectCount() {
        List<dev.langchain4j.agent.tool.ToolSpecification> specs = toolRegistry.toToolSpecifications();
        List<Map<String, Object>> schemas = toolRegistry.toJsonSchema();

        assertEquals(schemas.size(), specs.size(), "ToolSpecification 数量应与 JSON Schema 一致");
    }

    @Test
    void toToolSpecification_ShouldHaveCorrectStructure() {
        List<dev.langchain4j.agent.tool.ToolSpecification> specs = toolRegistry.toToolSpecifications();

        // 找到 test_greet
        dev.langchain4j.agent.tool.ToolSpecification greetSpec = null;
        for (dev.langchain4j.agent.tool.ToolSpecification spec : specs) {
            if ("test_greet".equals(spec.name())) {
                greetSpec = spec;
                break;
            }
        }
        assertNotNull(greetSpec, "应包含 test_greet");
        assertEquals("向指定用户发送问候", greetSpec.description());

        dev.langchain4j.agent.tool.ToolParameters params = greetSpec.parameters();
        assertNotNull(params);
        assertEquals("object", params.type());

        Map<String, Map<String, Object>> props = params.properties();
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("greeting"));

        List<String> required = params.required();
        assertEquals(1, required.size());
        assertEquals("name", required.get(0));
    }

    @Test
    void toToolSpecification_ShouldHandleNoParameterTool() {
        List<dev.langchain4j.agent.tool.ToolSpecification> specs = toolRegistry.toToolSpecifications();

        dev.langchain4j.agent.tool.ToolSpecification timeSpec = null;
        for (dev.langchain4j.agent.tool.ToolSpecification spec : specs) {
            if ("test_get_time".equals(spec.name())) {
                timeSpec = spec;
                break;
            }
        }
        assertNotNull(timeSpec, "应包含 test_get_time");
        assertTrue(timeSpec.parameters().properties().isEmpty());
    }

    // ==================== 白名单校验 ====================

    @Test
    void isAutoConfirm_ShouldReturnTrue_ForWhitelistedTools() {
        // send_notification 在 application.yml 的默认白名单中
        assertTrue(toolRegistry.isAutoConfirm("send_notification"));
        assertTrue(toolRegistry.isAutoConfirm("update_task_status"));
        assertTrue(toolRegistry.isAutoConfirm("feishu_send_message"));
    }

    @Test
    void isAutoConfirm_ShouldReturnFalse_ForNonWhitelistedTools() {
        // test_greet 和 test_get_time 不在白名单中
        assertFalse(toolRegistry.isAutoConfirm("test_greet"));
        assertFalse(toolRegistry.isAutoConfirm("test_get_time"));
        assertFalse(toolRegistry.isAutoConfirm("unknown_tool"));
    }

    // ==================== 工具执行 ====================

    @Test
    void execute_ShouldInvokeMethodAndReturnResult() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_1")
                .name("test_greet")
                .arguments("{\"name\":\"张三\",\"greeting\":\"你好\"}")
                .build();

        String result = toolRegistry.execute(request);
        assertEquals("你好, 张三!", result);
    }

    @Test
    void execute_ShouldHandleDefaultValue_WhenOptionalParamMissing() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_2")
                .name("test_greet")
                .arguments("{\"name\":\"World\"}")
                .build();

        String result = toolRegistry.execute(request);
        assertEquals("Hello, World!", result);
    }

    @Test
    void execute_ShouldHandleNoParameterTool() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_3")
                .name("test_get_time")
                .arguments("{}")
                .build();

        String result = toolRegistry.execute(request);
        assertEquals("2026-05-13 12:00:00", result);
    }

    @Test
    void execute_ShouldThrowException_WhenToolNotFound() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_4")
                .name("non_existent_tool")
                .arguments("{}")
                .build();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> toolRegistry.execute(request));
        assertTrue(exception.getMessage().contains("non_existent_tool") ||
                exception.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void execute_ShouldThrowException_WhenArgsParseFails() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call_5")
                .name("test_greet")
                .arguments("{invalid json}")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> toolRegistry.execute(request));
    }

    // ==================== 辅助方法 ====================

    /** 在 schema 列表中查找指定名称的工具 schema */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findSchemaByName(List<Map<String, Object>> schemas, String name) {
        for (Map<String, Object> schema : schemas) {
            Map<String, Object> function = (Map<String, Object>) schema.get("function");
            if (name.equals(function.get("name"))) {
                return schema;
            }
        }
        return null;
    }
}
