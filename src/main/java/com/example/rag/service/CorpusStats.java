package com.example.rag.service;

/**
 * BM25 打分所需的语料统计量。totalDocs 是分块总数，avgDocLength 是分块平均长度（字符数）。
 */
public record CorpusStats(long totalDocs, double avgDocLength) {
}
