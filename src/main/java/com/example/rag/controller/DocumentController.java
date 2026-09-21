package com.example.rag.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.rag.service.IngestionService;

/**
 * 文档上传入库接口（现在仅支持 UTF-8 编码的 .txt）。
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final IngestionService ingestionService;

    public DocumentController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
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
        if (filename == null || !filename.toLowerCase().endsWith(".txt")) {
            throw new IllegalArgumentException("目前仅支持 .txt 文件");
        }

        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        //id是数据库自动生成的自增主键
        Long documentId = ingestionService.ingest(filename, text);

        return Map.of("id", documentId, "name", filename, "status", "INGESTED");
    }
}
