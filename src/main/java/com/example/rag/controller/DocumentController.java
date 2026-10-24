package com.example.rag.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.rag.model.Document;
import com.example.rag.parser.DocumentParser;
import com.example.rag.service.IngestionService;

/**
 * 文档上传入库接口：支持 .txt（UTF-8 直读）与 .pdf/.html/.htm（Tika 解析为纯文本）。
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".pdf", ".html", ".htm");

    private final IngestionService ingestionService;

    private final DocumentParser documentParser;

    public DocumentController(IngestionService ingestionService, DocumentParser documentParser) {
        this.ingestionService = ingestionService;
        this.documentParser = documentParser;
    }

    /** 列出知识库中所有文档及其分块数。 */
    @GetMapping
    public List<Document> list() {
        return ingestionService.listDocuments();
    }

    /** 软删除文档及其全部分块（deleted 置 1，可恢复）。 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        ingestionService.deleteDocument(id);
    }

    /** 回收站：列出所有已删除文档。 */
    @GetMapping("/deleted")
    public List<Document> listDeleted() {
        return ingestionService.listDeletedDocuments();
    }

    /** 从回收站恢复文档及其全部分块（deleted 置回 0）。 */
    @PostMapping("/{id}/restore")
    public void restore(@PathVariable Long id) {
        ingestionService.restoreDocument(id);
    }

    /**
     * 上传并入库：multipart/form-data；前端表单里，文件参数名必须是 `file`
     */
    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IllegalArgumentException("无法获取文件名");
        }
        String lowerName = filename.toLowerCase();
        if (SUPPORTED_EXTENSIONS.stream().noneMatch(lowerName::endsWith)) {
            throw new IllegalArgumentException("仅支持 .txt/.pdf/.html/.htm 文件");
        }

        // txt 本身就是纯文本，按 UTF-8 直读；PDF/HTML 交给 Tika 提取正文
        String text = lowerName.endsWith(".txt")
                ? new String(file.getBytes(), StandardCharsets.UTF_8)
                : documentParser.parse(file.getInputStream());
        Long documentId = ingestionService.ingest(filename, text);

        return Map.of("id", documentId, "name", filename, "status", "INGESTED");
    }
}
