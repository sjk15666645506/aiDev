package com.deepseek.demo.annotation;

/**
 * API 工具归属领域，用于 DomainRouter 按领域分类。
 * 每个 ToolDomain 包含一个中文描述和关键词，供分类器匹配。
 */
public enum ToolDomain {

    TASK_MANAGEMENT("任务管理", "任务,工单,待办,jira,需求,story,缺陷,bug"),
    CODE_REPOSITORY("代码仓库", "代码,仓库,分支,pr,merge request,commit,github,gitlab"),
    CI_CD("CI/CD", "部署,发布,流水线,构建,jenkins,action,workflow"),
    MONITORING("监控告警", "监控,告警,metric,日志,链路,sentry,prometheus"),
    NOTIFICATION("消息通知", "通知,消息,飞书,钉钉,企微,webhook,发送"),
    DOCUMENT("文档知识库", "文档,知识库,confluence,notion,语雀,wiki"),
    SEARCH("搜索查询", "搜索,查询,检索,查找,全文搜索"),
    USER_MANAGEMENT("用户权限", "用户,组织,权限,角色,成员,部门"),
    FINANCE("金融", "股票,基金,金融,投资,净值,持仓,交易,买入,卖出,证券,行情,估值"),
    SYSTEM("系统", "系统,委派,代理,子任务,delegate,编排,协调");

    private final String displayName;
    private final String keywords;

    ToolDomain(String displayName, String keywords) {
        this.displayName = displayName;
        this.keywords = keywords;
    }

    public String getDisplayName() { return displayName; }
    public String getKeywords() { return keywords; }
}
