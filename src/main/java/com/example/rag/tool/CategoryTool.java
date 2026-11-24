package com.example.rag.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商品分类查询 Tool：只读 cloudMall 数据库。
 */
@Component
public class CategoryTool {

    private static final Logger log = LoggerFactory.getLogger(CategoryTool.class);

    private final JdbcTemplate cloudMallJdbc;

    public CategoryTool(@Qualifier("cloudMallJdbcTemplate") JdbcTemplate cloudMallJdbc) {
        this.cloudMallJdbc = cloudMallJdbc;
    }

    /** 返回所有一级分类及其下的二级分类。 */
    public String listCategories() {
        try {
            List<Map<String, Object>> all = cloudMallJdbc.queryForList(
                    "SELECT id, parent_id, name, level, product_count FROM pms_product_category ORDER BY parent_id, sort");
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> row : all) {
                int level = ((Number) row.get("level")).intValue();
                String indent = level == 0 ? "" : "  - ";
                lines.add(indent + row.get("name") + " (商品数: " + row.get("product_count") + ")");
            }
            if (lines.isEmpty()) {
                return "暂无商品分类";
            }
            return "商品分类:\n" + String.join("\n", lines);
        } catch (Exception e) {
            log.warn("分类查询失败: {}", e.getMessage());
            return "分类查询服务暂时不可用";
        }
    }
}
