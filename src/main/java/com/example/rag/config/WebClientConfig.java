package com.example.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.rag.rerank.BaiLianRerankClient;
import com.example.rag.rerank.NoopRerankClient;
import com.example.rag.rerank.RerankClient;


@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public RerankClient rerankClient(WebClient webClient, RerankProperties rerankProperties) {
        if (rerankProperties.isEnabled()) {
            return new BaiLianRerankClient(webClient, rerankProperties);
        }
        return new NoopRerankClient();
    }
}
