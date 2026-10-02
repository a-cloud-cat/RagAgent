package com.example.rag.controller;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 恒返回 200：就绪与否用字段表达，避免前端 500 和"后端未启动"两种失败混在一起
    @GetMapping("/retrieval")
    public Map<String, Boolean> retrieval() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Map.of("retrievalReady", true);
        } catch (DataAccessException e) {
            return Map.of("retrievalReady", false);
        }
    }
}
