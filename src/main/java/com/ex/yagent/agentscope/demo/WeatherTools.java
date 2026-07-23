package com.ex.yagent.agentscope.demo;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 自定义天气工具类
 */
public class WeatherTools {

    @Tool(name = "getWeather", description = "获取天气信息")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city
    ) {
        return "城市" + city + ",天气：晴天";
    }
}
