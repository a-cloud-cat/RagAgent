package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 商品库存查询 Tool：只读 cloudMall 数据库。
 */
@Component
public class StockTool {

    private static final Logger log = LoggerFactory.getLogger(StockTool.class);

    private final JdbcTemplate cloudMallJdbc;

    public StockTool(@Qualifier("cloudMallJdbcTemplate") JdbcTemplate cloudMallJdbc) {
        this.cloudMallJdbc = cloudMallJdbc;
    }

    /** 按商品 ID 查询所有 SKU 的价格、库存和销量。 */
    public String queryStock(Long productId) {
        if (productId == null) {
            return "请提供商品 ID";
        }
        try {
            List<Map<String, Object>> skus = cloudMallJdbc.queryForList(
                    "SELECT sku_code, price, stock, low_stock, sale, promotion_price, sp_data FROM pms_sku_stock WHERE product_id = ?",
                    productId);
            if (skus.isEmpty()) {
                return "未找到商品 " + productId + " 的库存信息";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("商品 ").append(productId).append(" 库存信息:\n");
            int totalStock = 0;
            for (Map<String, Object> sku : skus) {
                sb.append("  SKU: ").append(sku.get("sku_code")).append("\n");
                sb.append("    价格: ").append(sku.get("price")).append(" 元");
                if (sku.get("promotion_price") != null) {
                    sb.append(" (促销价: ").append(sku.get("promotion_price")).append(" 元)");
                }
                sb.append("\n");
                sb.append("    库存: ").append(sku.get("stock"));
                if (sku.get("low_stock") != null) {
                    sb.append(" (预警线: ").append(sku.get("low_stock")).append(")");
                }
                sb.append("\n");
                sb.append("    销量: ").append(sku.get("sale")).append("\n");
                if (sku.get("sp_data") != null) {
                    sb.append("    规格: ").append(sku.get("sp_data")).append("\n");
                }
                totalStock += ((Number) sku.get("stock")).intValue();
            }
            sb.append("合计库存: ").append(totalStock);
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("库存查询失败 productId={}: {}", productId, e.getMessage());
            return "库存查询服务暂时不可用";
        }
    }
}
