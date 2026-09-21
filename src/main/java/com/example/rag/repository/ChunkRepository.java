package com.example.rag.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.rag.model.Chunk;

/**
 * 文档分块与向量数据访问。
 */
@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量写入分块及其向量（一个事务，全部成功或全部回滚）。
     */
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
     * @param queryEmbedding 查询向量
     * @param topK           返回条数
     */
    public List<Chunk> search(float[] queryEmbedding, int topK) {
        PGobject vector = toPgVector(queryEmbedding);

        return jdbcTemplate.query(
                "SELECT id, document_id, chunk_index, content, "
                        + "1 - (embedding <=> ?) AS score "
                        + "FROM t_chunk WHERE deleted = 0 AND embedding IS NOT NULL "
                        + "ORDER BY embedding <=> ? LIMIT ?",
                ps -> {
                    ps.setObject(1, vector);
                    ps.setObject(2, vector);
                    ps.setInt(3, topK);
                },
                this::mapRow);
    }

    /**行映射回调方法（RowMapper），配合jdbcTemplate.query()使用**/
    private Chunk mapRow(ResultSet rs, int rowNum) throws SQLException {
        Chunk chunk = new Chunk();
        chunk.setId(rs.getLong("id"));
        chunk.setDocumentId(rs.getLong("document_id"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setContent(rs.getString("content"));
        chunk.setScore(rs.getDouble("score"));
        return chunk;
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
