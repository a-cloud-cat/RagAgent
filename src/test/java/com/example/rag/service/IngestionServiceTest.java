package com.example.rag.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.rag.embedding.EmbeddingClient;
import com.example.rag.model.Document;
import com.example.rag.repository.ChunkRepository;
import com.example.rag.repository.DocumentRepository;

class IngestionServiceTest {

    private DocumentRepository documentRepository;

    private ChunkRepository chunkRepository;

    private IngestionService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        chunkRepository = mock(ChunkRepository.class);
        service = new IngestionService(documentRepository, chunkRepository,
                mock(TextChunker.class), mock(EmbeddingClient.class));
    }

    @Test
    void listDocuments委托给repository查询未删除文档() {
        when(documentRepository.listAll()).thenReturn(List.of(new Document()));

        List<Document> result = service.listDocuments();

        assertEquals(1, result.size());
        verify(documentRepository).listAll();
    }

    @Test
    void listDeletedDocuments委托给repository查询已删除文档() {
        when(documentRepository.listDeleted()).thenReturn(List.of(new Document(), new Document()));

        List<Document> result = service.listDeletedDocuments();

        assertEquals(2, result.size());
        verify(documentRepository).listDeleted();
    }

    @Test
    void deleteDocument级联软删文档及其分块() {
        service.deleteDocument(42L);

        verify(documentRepository).markDeleted(42L);
        verify(chunkRepository).markDeletedByDocumentId(42L);
    }

    @Test
    void restoreDocument级联恢复文档及其分块() {
        service.restoreDocument(42L);

        verify(documentRepository).restore(42L);
        verify(chunkRepository).restoreByDocumentId(42L);
    }
}
