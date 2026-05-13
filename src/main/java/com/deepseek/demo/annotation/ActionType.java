package com.deepseek.demo.annotation;

/**
 * 操作类型枚举，用于标记 API 的风险等级。
 * <ul>
 *   <li>READ：读操作，自动执行，无需额外确认</li>
 *   <li>WRITE：写操作，非白名单的需要二次确认</li>
 * </ul>
 */
public enum ActionType {
    READ,
    WRITE
}
