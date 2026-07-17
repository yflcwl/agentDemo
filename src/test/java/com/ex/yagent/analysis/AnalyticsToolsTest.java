package com.ex.yagent.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsToolsTest {

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    private AnalyticsTools analyticsTools;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:analyticsdbtest;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("schema.sql"),
                new ClassPathResource("data.sql")
        );
        populator.execute(dataSource);

        jdbcTemplate = new JdbcTemplate(dataSource);
        analyticsTools = new AnalyticsTools(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP ALL OBJECTS");
    }

    @Test
    void shouldLoadSampleOrders() {
        Integer count = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        assertTrue(count != null && count > 0);
    }

    @Test
    void shouldExecuteReadOnlySql() {
        String result = analyticsTools.runReadOnlySql("""
                select region_code, sum(order_amount) as sales_amount
                from orders
                group by region_code
                order by sales_amount desc
                """);

        assertTrue(result.contains("\"rowCount\""));
        assertTrue(result.contains("sales_amount"));
    }

    @Test
    void shouldRejectMutatingSql() {
        assertThrows(IllegalArgumentException.class,
                () -> analyticsTools.runReadOnlySql("delete from orders where order_id = 30001"));
    }

    @Test
    void shouldReturnNoDataMessageForEmptyResult() {
        String result = analyticsTools.runReadOnlySql("""
                select *
                from orders
                where order_date > DATE '2030-01-01'
                """);

        assertTrue(result.contains("未查到符合条件的数据"));
    }
}
