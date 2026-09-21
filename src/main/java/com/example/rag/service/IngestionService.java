package com.example.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.model.Chunk;
import com.example.rag.repository.ChunkRepository;
import com.example.rag.repository.DocumentRepository;

/**
 * 文档入库编排：建文档记录(PENDING) → 切块 → 批量向量化 → 向量落库 → 置 INGESTED；
 * 任一步失败置 FAILED，便于在 t_document 中观察失败文档。
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** 单次向量化请求的最大文本条数（百炼接口有批量上限，16 是保守值） */
    private static final int EMBED_BATCH_SIZE = 16;

    private final DocumentRepository documentRepository;

    private final ChunkRepository chunkRepository;

    private final TextChunker textChunker;

    private final EmbeddingClient embeddingClient;

    public IngestionService(DocumentRepository documentRepository,
                            ChunkRepository chunkRepository,
                            TextChunker textChunker,
                            EmbeddingClient embeddingClient) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 入库一份文本文档。
     *
     * @return 文档 ID
     */
    public Long ingest(String filename, String text) {
        Long documentId = documentRepository.insert(filename, "PENDING");
        try {
            List<String> chunkTexts = textChunker.split(text);
            if (chunkTexts.isEmpty()) {
                throw new IllegalArgumentException("文档内容为空");
            }

            List<float[]> embeddings = embedInBatches(chunkTexts);

            List<Chunk> chunks = new ArrayList<>(chunkTexts.size());
            for (int i = 0; i < chunkTexts.size(); i++) {
                chunks.add(new Chunk(documentId, i, chunkTexts.get(i), embeddings.get(i)));
            }
            chunkRepository.batchInsert(chunks);

            documentRepository.updateStatus(documentId, "INGESTED");
            log.info("文档入库成功：id={}, name={}, chunks={}", documentId, filename, chunks.size());
            return documentId;
        } catch (Exception e) {
            log.error("文档入库失败：id={}, name={}", documentId, filename, e);
            documentRepository.updateStatus(documentId, "FAILED");
            throw new IllegalStateException("文档入库失败：" + e.getMessage(), e);
        }
    }

    /** 分批调用向量化接口，按原顺序拼回全部向量。 */
    private List<float[]> embedInBatches(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(from + EMBED_BATCH_SIZE, texts.size());
            all.addAll(embeddingClient.embed(texts.subList(from, to)));
        }
        if (all.size() != texts.size()) {
            throw new IllegalStateException(
                    "向量化返回条数不一致：输入 " + texts.size() + "，返回 " + all.size());
        }
        return all;
    }
}
