package com.example.rag.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.rag.model.KnowledgeBase;
import com.example.rag.repository.KnowledgeBaseRepository;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseController(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /** 列出所有知识库，供前端下拉框选择。 */
    @GetMapping
    public Map<String, Object> list() {
        List<KnowledgeBase> list = knowledgeBaseRepository.listAll();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", list);
        return result;
    }
}
