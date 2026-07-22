package com.ex.yagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(
        excludeName = {
                "com.alibaba.cloud.ai.autoconfigure.dashscope." +
                        "DashScopeMultimodalEmbeddingAutoConfiguration"
        }
)
@ConfigurationPropertiesScan
public class YagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(YagentApplication.class, args);
    }

}
