package com.example.rag.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.rag.config.RerankProperties;
import com.example.rag.config.RetrievalProperties;
import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.model.Chunk;
import com.example.rag.repository.ChunkRepository;
import com.example.rag.rerank.RerankClient;

class RetrievalServiceTest {

    private EmbeddingClient embeddingClient;

    private ChunkRepository chunkRepository;

    private RerankClient rerankClient;

    private RetrievalProperties retrievalProperties;

    private RerankProperties rerankProperties;

    private RetrievalService service;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(EmbeddingClient.class);
        chunkRepository = mock(ChunkRepository.class);
        rerankClient = mock(RerankClient.class);
        retrievalProperties = new RetrievalProperties();
        rerankProperties = new RerankProperties();
        service = new RetrievalService(embeddingClient, chunkRepository,
                rerankClient, retrievalProperties, rerankProperties);

        when(embeddingClient.embed(anyList())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(chunkRepository.search(any(float[].class), anyInt()))
                .thenReturn(List.of(chunk("d1", 0, 0.9), chunk("d2", 1, 0.5), chunk("d3", 2, 0.3)));
    }

    private Chunk chunk(String document, int index, double score) {
        Chunk c = new Chunk();
        c.setDocumentName(document);
        c.setChunkIndex(index);
        c.setContent("内容 " + index);
        c.setScore(score);
        return c;
    }

    private void stubRerank(double top1Score) {
        when(rerankClient.rerank(any(), anyList(), anyInt())).thenReturn(
                List.of(chunk("d1", 0, top1Score), chunk("d2", 1, 0.4), chunk("d3", 2, 0.2)));
    }

    @Test
    void rerank关闭时闸门恒放行() {
        rerankProperties.setEnabled(false);
        stubRerank(0.1);

        RetrievalOutcome outcome = service.retrieve("问题");

        assertTrue(outcome.isEvidenceReady());
        assertEquals(3, outcome.getChunks().size());
    }

    @Test
    void top1高于阈值时放行() {
        rerankProperties.setEnabled(true);
        stubRerank(0.9);

        RetrievalOutcome outcome = service.retrieve("问题");

        assertTrue(outcome.isEvidenceReady());
        assertEquals(0.9, outcome.getTop1Score(), 1e-9);
    }

    @Test
    void top1低于阈值时拦截() {
        rerankProperties.setEnabled(true);
        stubRerank(0.2);

        RetrievalOutcome outcome = service.retrieve("问题");

        assertFalse(outcome.isEvidenceReady());
        // 拦截不丢召回块，闸门只是标记，拒答由上层决定
        assertEquals(3, outcome.getChunks().size());
    }

    @Test
    void 阈值边界值等于时放行() {
        rerankProperties.setEnabled(true);
        stubRerank(retrievalProperties.getEvidenceThreshold());

        RetrievalOutcome outcome = service.retrieve("问题");

        assertTrue(outcome.isEvidenceReady());
    }

    @Test
    void rerank异常时降级放行() {
        rerankProperties.setEnabled(true);
        when(rerankClient.rerank(any(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("rerank 挂了"));

        RetrievalOutcome outcome = service.retrieve("问题");

        assertTrue(outcome.isEvidenceReady());
        assertEquals(3, outcome.getChunks().size());
    }

    @Test
    void 无候选时按无证据处理() {
        when(chunkRepository.search(any(float[].class), anyInt())).thenReturn(List.of());

        RetrievalOutcome outcome = service.retrieve("问题");

        assertTrue(outcome.getChunks().isEmpty());
        assertFalse(outcome.isEvidenceReady());
        assertEquals(0, outcome.getTop1Score(), 1e-9);
    }
}
