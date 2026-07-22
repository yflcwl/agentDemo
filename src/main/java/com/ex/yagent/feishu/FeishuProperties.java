package com.ex.yagent.feishu;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    /**
     * 飞书开放平台 API 根路径，默认使用线上地址。
     */
    private String baseUrl = "https://open.feishu.cn/open-apis";

    /**
     * 飞书自建应用 app_id。
     */
    private String appId;

    /**
     * 飞书自建应用 app_secret。
     */
    private String appSecret;

    /**
     * 可选：默认操作的文档 ID。
     */
    private String defaultDocumentId;
}
