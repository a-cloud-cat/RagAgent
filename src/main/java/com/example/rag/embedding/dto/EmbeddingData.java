package com.example.rag.embedding.dto;


/**
 * 单条向量结果：JSON 的 embedding 数组会被 Jackson 自动映射成 float[]。
 * 本类使用 Java 17 Record(记录类)特性：仅声明字段，编译器自动生成构造方法、访问方法、equals、hashCode、toString；
 * 字段默认为 final，对象创建后不可修改，适合用作纯数据传输DTO，无需手写get/set。
 */
public record EmbeddingData(int index, float[] embedding) {
}
