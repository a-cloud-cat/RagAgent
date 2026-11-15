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
        // embedding 批量返回多个 1536 维向量，默认 256KB 缓冲不够
        return builder.codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
    }

    @Bean
    public RerankClient rerankClient(WebClient webClient, RerankProperties rerankProperties) {
        if (rerankProperties.isEnabled()) {
            return new BaiLianRerankClient(webClient, rerankProperties);
        }
        return new NoopRerankClient();
    }
}
