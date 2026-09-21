package com.example.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.rag.config.EmbeddingProperties;
import com.example.rag.config.LlmProperties;
import com.example.rag.config.RetrievalProperties;


@SpringBootApplication
@EnableConfigurationProperties({LlmProperties.class, EmbeddingProperties.class, RetrievalProperties.class})
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
        System.out.println("http://127.0.0.1:8080");
    }
}
