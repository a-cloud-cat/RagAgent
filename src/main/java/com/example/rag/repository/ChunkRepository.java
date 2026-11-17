package com.example.rag.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.rag.model.Chunk;
import com.example.rag.service.CorpusStats;

/**
 * 文档分块与向量数据访问。
 */
@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 批量写入分块及其向量（一个事务，全部成功或全部回滚）。 */
    @Transactional
    public void batchInsert(List<Chunk> chunks) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO t_chunk (document_id, chunk_index, content, embedding, create_time) "
                        + "VALUES (?, ?, ?, ?::vector, CURRENT_TIMESTAMP)",
                chunks,
                chunks.size(),
                (ps, chunk) -> {
                    ps.setLong(1, chunk.getDocumentId());
                    ps.setInt(2, chunk.getChunkIndex());
                    ps.setString(3, chunk.getContent());
                    ps.setObject(4, toPgVector(chunk.getEmbedding()));
                });
    }

    /**
     * 按余弦距离检索最相似的 topK 个分块。
     *
     * @param queryEmbedding    查询向量
     * @param topK              返回条数
     * @param knowledgeBaseId   可空；指定知识库时只搜该库下的文档，null 表示全库
     */
    public List<Chunk> search(float[] queryEmbedding, int topK, Long knowledgeBaseId) {
        PGobject vector = toPgVector(queryEmbedding);

        String sql = "SELECT c.id, c.document_id, d.name AS document_name, c.chunk_index, c.content, "
                + "1 - (c.embedding <=> ?) AS score "
                + "FROM t_chunk c JOIN t_document d ON c.document_id = d.id "
                + "WHERE c.deleted = 0 AND d.deleted = 0 AND c.embedding IS NOT NULL ";
        if (knowledgeBaseId != null) {
            sql += "AND d.knowledge_base_id = ? ";
        }
        sql += "ORDER BY c.embedding <=> ? LIMIT ?";

        return jdbcTemplate.query(sql, ps -> {
            ps.setObject(1, vector);
            if (knowledgeBaseId != null) {
                ps.setLong(2, knowledgeBaseId);
                ps.setObject(3, vector);
                ps.setInt(4, topK);
            } else {
                ps.setObject(2, vector);
                ps.setInt(3, topK);
            }
        }, this::mapRow);
    }

    /** 全库检索（不区分知识库），保持原有调用不变。 */
    public List<Chunk> search(float[] queryEmbedding, int topK) {
        return search(queryEmbedding, topK, null);
    }

    /**
     * 基于关键词的 ILIKE 召回：命中任意一个关键词的分块都算候选，
     * 按命中关键词数量降序排列，取前 limit 条。
     */
    public List<Chunk> searchByKeywords(List<String> keywords, int limit, Long knowledgeBaseId) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder(
                "WHERE c.deleted = 0 AND d.deleted = 0 AND (");
        StringBuilder orderBy = new StringBuilder("ORDER BY (");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                where.append(" OR ");
                orderBy.append(" + ");
            }
            where.append("c.content ILIKE ?");
            orderBy.append("CASE WHEN c.content ILIKE ? THEN 1 ELSE 0 END");
        }
        where.append(") ");
        if (knowledgeBaseId != null) {
            where.append("AND d.knowledge_base_id = ? ");
        }
        orderBy.append(") DESC, c.id ASC LIMIT ?");

        String sql = "SELECT c.id, c.document_id, d.name AS document_name, c.chunk_index, c.content, "
                + "0 AS score "
                + "FROM t_chunk c JOIN t_document d ON c.document_id = d.id "
                + where + orderBy;

        // 参数顺序：先 WHERE 子句（keywords.size 个 ILIKE + 可选 kbId），再 ORDER BY（keywords.size 个 ILIKE），最后 LIMIT
        return jdbcTemplate.query(sql, ps -> {
            int idx = 1;
            for (String kw : keywords) {
                ps.setString(idx++, "%" + kw + "%");
            }
            if (knowledgeBaseId != null) {
                ps.setLong(idx++, knowledgeBaseId);
            }
            for (String kw : keywords) {
                ps.setString(idx++, "%" + kw + "%");
            }
            ps.setInt(idx, limit);
        }, this::mapRow);
    }

    /**
     * 获取语料统计：分块总数与平均分块长度（字符数），用于 BM25 的 IDF 和长度归一化。
     */
    public CorpusStats getCorpusStats(Long knowledgeBaseId) {
        String sql = "SELECT COUNT(*) AS total, COALESCE(AVG(LENGTH(content)), 0) AS avg_len "
                + "FROM t_chunk c JOIN t_document d ON c.document_id = d.id "
                + "WHERE c.deleted = 0 AND d.deleted = 0";
        if (knowledgeBaseId != null) {
            sql += " AND d.knowledge_base_id = ?";
        }
        Long kbId = knowledgeBaseId;
        return jdbcTemplate.query(sql, rs -> {
            if (rs.next()) {
                return new CorpusStats(rs.getLong("total"), rs.getDouble("avg_len"));
            }
            return new CorpusStats(0, 0);
        }, kbId == null ? new Object[0] : new Object[]{kbId});
    }

    /**
     * 统计每个查询词在多少个分块中出现过（document frequency），用于 BM25 的 IDF 计算。
     * 一条 SQL 用 CASE WHEN 聚合所有词，避免 N 次查询。
     */
    public Map<String, Long> countTermDocFreq(List<String> terms, Long knowledgeBaseId) {
        if (terms == null || terms.isEmpty()) {
            return Map.of();
        }

        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) {
                select.append(", ");
            }
            select.append("COUNT(*) FILTER (WHERE c.content ILIKE ?) AS term_").append(i);
        }
        select.append(" FROM t_chunk c JOIN t_document d ON c.document_id = d.id "
                + "WHERE c.deleted = 0 AND d.deleted = 0");
        if (knowledgeBaseId != null) {
            select.append(" AND d.knowledge_base_id = ?");
        }

        Object[] args;
        if (knowledgeBaseId != null) {
            args = new Object[terms.size() + 1];
            for (int i = 0; i < terms.size(); i++) {
                args[i] = "%" + terms.get(i) + "%";
            }
            args[terms.size()] = knowledgeBaseId;
        } else {
            args = new Object[terms.size()];
            for (int i = 0; i < terms.size(); i++) {
                args[i] = "%" + terms.get(i) + "%";
            }
        }

        return jdbcTemplate.query(select.toString(), rs -> {
            Map<String, Long> result = new HashMap<>();
            if (rs.next()) {
                for (int i = 0; i < terms.size(); i++) {
                    result.put(terms.get(i), rs.getLong("term_" + i));
                }
            }
            return result;
        }, args);
    }

    private Chunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        Chunk chunk = new Chunk();
        chunk.setId(rs.getLong("id"));
        chunk.setDocumentId(rs.getLong("document_id"));
        chunk.setDocumentName(rs.getString("document_name"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setContent(rs.getString("content"));
        chunk.setScore(rs.getDouble("score"));
        return chunk;
    }

    /** 级联软删除某文档下的全部分块，与文档软删除同事务。 */
    public int markDeletedByDocumentId(Long documentId) {
        return jdbcTemplate.update(
                "UPDATE t_chunk SET deleted = 1 WHERE document_id = ? AND deleted = 0",
                documentId);
    }

    /** 级联恢复某文档下的全部分块，与文档恢复同事务。 */
    public int restoreByDocumentId(Long documentId) {
        return jdbcTemplate.update(
                "UPDATE t_chunk SET deleted = 0 WHERE document_id = ? AND deleted = 1",
                documentId);
    }

    /** float[] -> pgvector 参数对象 */
    private PGobject toPgVector(float[] values) {
        try {
            PGobject pg = new PGobject();
            pg.setType("vector");
            pg.setValue(toVectorLiteral(values));
            return pg;
        } catch (SQLException e) {
            // setValue 只在拼写字面量失败时抛错，正常路径不会走到
            throw new IllegalStateException("向量参数构造失败", e);
        }
    }

    /** float[] -> pgvector 的字面量 如"[1.0,2.0]" */
    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
