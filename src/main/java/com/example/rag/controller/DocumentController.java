package com.example.rag.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.rag.parser.DocumentParser;
import com.example.rag.service.IngestionService;

/**
 * 文档上传入库接口：支持 .txt（UTF-8 直读）与 .pdf/.html/.htm（Tika 解析为纯文本）。
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    /** 当前接口承诺支持的文件后缀白名单 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt", ".pdf", ".html", ".htm");

    private final IngestionService ingestionService;

    private final DocumentParser documentParser;

    public DocumentController(IngestionService ingestionService, DocumentParser documentParser) {
        this.ingestionService = ingestionService;
        this.documentParser = documentParser;
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
        //id是数据库自动生成的自增主键
        Long documentId = ingestionService.ingest(filename, text);

        return Map.of("id", documentId, "name", filename, "status", "INGESTED");
    }
}
