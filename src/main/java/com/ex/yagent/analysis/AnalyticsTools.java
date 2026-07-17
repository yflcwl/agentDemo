package com.ex.yagent.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AnalyticsTools {

    private static final Pattern WRITE_SQL_PATTERN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|merge|call|create|grant|revoke|comment)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern READONLY_PREFIX_PATTERN = Pattern.compile(
            "^(select|with)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyticsTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(name = "getSchemaInfo", description = "返回经营分析样例库的表结构、字段业务含义和 H2 SQL 使用约定")
    public String getSchemaInfo() {
        return """
                经营分析样例库 schema:

                1. orders
                - order_id: 订单ID
                - order_date: 订单日期
                - customer_id: 客户ID
                - product_id: 商品ID
                - region_code: 地区编码
                - quantity: 购买数量
                - unit_price: 商品单价
                - order_amount: 订单金额，等于 quantity * unit_price

                2. products
                - product_id: 商品ID
                - product_name: 商品名称
                - category_name: 商品品类

                3. customers
                - customer_id: 客户ID
                - customer_name: 客户名称
                - customer_level: 客户等级

                4. regions
                - region_code: 地区编码
                - region_name: 地区名称

                关联关系:
                - orders.customer_id = customers.customer_id
                - orders.product_id = products.product_id
                - orders.region_code = regions.region_code

                分析口径:
                - 销售额: SUM(order_amount)
                - 订单数: COUNT(DISTINCT order_id)
                - 客单价: SUM(order_amount) / NULLIF(COUNT(DISTINCT order_id), 0)

                H2 SQL 约定:
                - 近30天可使用: order_date >= DATEADD('DAY', -30, CURRENT_DATE)
                - 本月可使用: YEAR(order_date) = YEAR(CURRENT_DATE) AND MONTH(order_date) = MONTH(CURRENT_DATE)
                - 月度趋势可使用: FORMATDATETIME(order_date, 'yyyy-MM')
                - 只允许 SELECT 或 WITH 开头的只读 SQL
                """;
    }

    @Tool(name = "listSupportedMetrics", description = "返回当前经营分析 Demo 支持的指标、维度和典型问题类型")
    public String listSupportedMetrics() {
        List<Map<String, Object>> metrics = List.of(
                metric("销售额", List.of("日期", "地区", "商品", "品类", "客户"), List.of("趋势", "排行", "对比", "摘要")),
                metric("订单数", List.of("日期", "地区", "商品", "品类", "客户"), List.of("趋势", "排行", "对比", "摘要")),
                metric("客单价", List.of("日期", "地区", "客户等级"), List.of("趋势", "对比", "摘要"))
        );
        return toJson(metrics);
    }

    @Tool(name = "runReadOnlySql", description = "在 H2 经营分析样例库中执行只读 SQL，并返回 JSON 格式的结果")
    public String runReadOnlySql(
            @ToolParam(name = "sql", description = "只读 H2 SQL，必须以 SELECT 或 WITH 开头") String sql
    ) {
        String normalizedSql = normalizeSql(sql);
        validateReadOnlySql(normalizedSql);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(normalizedSql);
        List<Map<String, Object>> limitedRows = rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sql", normalizedSql);
        payload.put("rowCount", rows.size());
        payload.put("truncated", rows.size() > MAX_ROWS);
        payload.put("rows", limitedRows);
        if (rows.isEmpty()) {
            payload.put("message", "未查到符合条件的数据");
        }
        return toJson(payload);
    }

    private Map<String, Object> metric(String metricName, List<String> dimensions, List<String> questionTypes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metric", metricName);
        payload.put("dimensions", dimensions);
        payload.put("questionTypes", questionTypes);
        return payload;
    }

    private String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String normalized = sql.trim();
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private void validateReadOnlySql(String sql) {
        if (!READONLY_PREFIX_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("只允许执行 SELECT 或 WITH 开头的只读 SQL");
        }
        if (WRITE_SQL_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("检测到非只读 SQL 关键字，已拒绝执行");
        }
        if (sql.contains(";")) {
            throw new IllegalArgumentException("不允许执行多条 SQL 语句");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化工具结果失败", e);
        }
    }
}
