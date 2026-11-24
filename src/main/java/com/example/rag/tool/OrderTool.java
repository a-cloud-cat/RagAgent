package com.example.rag.tool;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单查询 Tool：只读 cloudMall 数据库。
 */
@Component
public class OrderTool {

    private static final Logger log = LoggerFactory.getLogger(OrderTool.class);

    private static final String STATUS_TEXT = "0:待付款,1:待发货,2:已发货,3:已完成,4:已关闭";
    private static final String PAY_TYPE_TEXT = "0:未支付,1:支付宝,2:微信";

    private final JdbcTemplate cloudMallJdbc;

    public OrderTool(@Qualifier("cloudMallJdbcTemplate") JdbcTemplate cloudMallJdbc) {
        this.cloudMallJdbc = cloudMallJdbc;
    }

    /** 根据订单号查询订单状态、物流及商品明细。 */
    public String queryOrder(String orderSn) {
        if (orderSn == null || orderSn.isBlank()) {
            return "请提供订单编号";
        }
        try {
            Map<String, Object> order = cloudMallJdbc.queryForMap(
                    "SELECT order_sn, member_username, total_amount, pay_amount, pay_type, status, " +
                    "create_time, receiver_name, receiver_phone, receiver_address, " +
                    "delivery_company, delivery_sn FROM oms_order WHERE order_sn = ?",
                    orderSn);
            List<Map<String, Object>> items = cloudMallJdbc.queryForList(
                    "SELECT product_name, product_price, product_quantity FROM oms_order_item WHERE order_sn = ?",
                    orderSn);
            return formatOrder(order, items);
        } catch (Exception e) {
            log.warn("订单查询失败 orderSn={}: {}", orderSn, e.getMessage());
            return "未找到订单 " + orderSn;
        }
    }

    private String formatOrder(Map<String, Object> order, List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单号: ").append(order.get("order_sn")).append("\n");
        sb.append("用户: ").append(order.get("member_username")).append("\n");
        sb.append("订单金额: ").append(order.get("total_amount")).append(" 元\n");
        sb.append("实付金额: ").append(order.get("pay_amount")).append(" 元\n");
        sb.append("支付方式: ").append(decode((Integer) order.get("pay_type"), PAY_TYPE_TEXT)).append("\n");
        sb.append("订单状态: ").append(decode((Integer) order.get("status"), STATUS_TEXT)).append("\n");
        sb.append("下单时间: ").append(order.get("create_time")).append("\n");
        sb.append("收货人: ").append(order.get("receiver_name"));
        sb.append(" / ").append(order.get("receiver_phone")).append("\n");
        sb.append("收货地址: ").append(order.get("receiver_address")).append("\n");
        Object deliveryCompany = order.get("delivery_company");
        Object deliverySn = order.get("delivery_sn");
        if (deliveryCompany != null) {
            sb.append("物流公司: ").append(deliveryCompany).append("\n");
        }
        if (deliverySn != null) {
            sb.append("物流单号: ").append(deliverySn).append("\n");
        }
        if (items != null && !items.isEmpty()) {
            sb.append("商品明细:\n");
            for (Map<String, Object> item : items) {
                sb.append("  - ").append(item.get("product_name"))
                  .append(" × ").append(item.get("product_quantity"))
                  .append(" (").append(item.get("product_price")).append(" 元)\n");
            }
        }
        return sb.toString().trim();
    }

    private String decode(Integer code, String mapping) {
        if (code == null) return "未知";
        for (String pair : mapping.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2 && kv[0].equals(String.valueOf(code))) {
                return kv[1];
            }
        }
        return "未知(" + code + ")";
    }
}
