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

    // 返回 200：数据库是否成功就绪用字段表达，避免数据库问题和其他后端bug问题都返回500
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
