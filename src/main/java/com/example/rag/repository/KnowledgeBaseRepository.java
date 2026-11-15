package com.example.rag.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.rag.model.KnowledgeBase;

@Repository
public class KnowledgeBaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<KnowledgeBase> listAll() {
        return jdbcTemplate.query(
                "SELECT id, name, description, create_time FROM t_knowledge_base ORDER BY id",
                this::mapRow);
    }

    public KnowledgeBase findById(Long id) {
        List<KnowledgeBase> list = jdbcTemplate.query(
                "SELECT id, name, description, create_time FROM t_knowledge_base WHERE id = ?",
                this::mapRow, id);
        return list.isEmpty() ? null : list.get(0);
    }

    private KnowledgeBase mapRow(ResultSet rs, int rowNum) throws SQLException {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(rs.getLong("id"));
        kb.setName(rs.getString("name"));
        kb.setDescription(rs.getString("description"));
        if (rs.getTimestamp("create_time") != null) {
            kb.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        }
        return kb;
    }
}
