package com.deepseek.demo.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * 工具参数的元数据注解，用于描述 {@link Tool} 的每个参数。
 * 每个实例对应 JSON Schema 中的一个 property。
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /** 参数名称，作为 JSON Schema property 的 key */
    String name();

    /** 参数类型，支持 "string"、"number"、"boolean" */
    String type() default "string";

    /** 参数描述，帮助 LLM 理解参数的用途 */
    String description();

    /** 是否为必需参数 */
    boolean required() default false;
}
