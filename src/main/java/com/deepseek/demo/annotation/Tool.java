package com.deepseek.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 LLM 可调用的工具。
 * 被标注的方法会被 {@link com.deepseek.demo.service.ToolRegistry}
 * 扫描并注册为 DeepSeek function calling 的可用工具。
 * <p>
 * 方法参数必须与 {@link #parameters()} 中的定义一一对应，
 * 且方法签名应使用 String 类型接收所有参数（由 Jackson 反序列化后传入）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /** 工具名称，用于 DeepSeek tool_call 中的 function name */
    String name();

    /** 工具描述，帮助 LLM 理解何时调用此工具 */
    String description();

    /** 工具的参数列表 */
    ToolParam[] parameters() default {};

    /** 操作类型，决定是否需要二次确认 */
    ActionType action() default ActionType.READ;
}
