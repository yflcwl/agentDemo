package com.ex.yagent.agentscope;

import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import redis.clients.jedis.JedisPooled;

public class AgentStateStoreDemo {
    public static void main(String[] args) {

        final String model = "dashscope:qwen-plus";
        final String workspace = ".agentscope/workspace";

        // 默认(单机):省略 .stateStore(...) 即可,自动用本地 JsonFileAgentStateStore
        HarnessAgent agent = HarnessAgent.builder()
                .name("MyAgent")
                .model(model)
                .workspace(workspace)
                .build();
        System.out.println(agent.call(new UserMessage("你好！")).block().getTextContent());

        // 多副本生产:使用 DistributedStore
        JedisPooled jedis = new JedisPooled("redis://localhost:6379");
        HarnessAgent agenta = HarnessAgent.builder()
                .name("MyAgent")
                .model(model)
                .workspace(workspace)
                .stateStore(RedisAgentStateStore.builder().build())
                .distributedStore(RedisDistributedStore.fromJedis(jedis))
                .build();
        System.out.println(agenta.call(new UserMessage("Hello World!")).block().getTextContent());

    }
}
