package com.ex.yagent.demo.invoke;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;

public class HttpAiInvoke {

    public static void main(String[] args) {
        // 获取 API Key（建议从环境变量或配置文件中读取）
        String apiKey = TestApiKey.API_KEY;

        // 构建请求体
        JSONObject requestBody = new JSONObject();

        // model
        requestBody.set("model", "qwen3.7-max");

        // input
        JSONObject input = new JSONObject();
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", "你是谁？");
        input.put("messages", new JSONObject[]{message});
        requestBody.set("input", input);

        // parameters
        JSONObject parameters = new JSONObject();
        parameters.set("enable_thinking", true);
        parameters.set("result_format", "message");
        requestBody.set("parameters", parameters);

        // 发送 POST 请求
        String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

        try (HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .execute()) {

            // 获取响应结果
            String responseBody = response.body();
            System.out.println("Status: " + response.getStatus());
            System.out.println("Response: " + responseBody);

            // 如果需要解析响应
            // JSONObject result = JSONUtil.parseObj(responseBody);
            // 处理响应数据...

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
