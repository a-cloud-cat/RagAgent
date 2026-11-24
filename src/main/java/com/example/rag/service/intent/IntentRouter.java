package com.example.rag.service.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 Pattern 关键词匹配的轻量意图识别器。
 * 不引入 LLM / Embedding，零额外 API 调用，纯本地正则。
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    /** RAG_QUERY 为默认意图——匹配不到任何 Pattern 就走知识库检索。 */

    private final List<IntentRule> rules;

    public IntentRouter() {
        this.rules = new ArrayList<>();
        // 订单查询：订单号（纯数字或前缀+数字）、物流、退货相关
        rules.add(new IntentRule(Intent.ORDER_QUERY, List.of(
                // 匹配订单号格式：20241027001 这种
                Pattern.compile("\\d{10,20}"),
                Pattern.compile("订单.?号?"),
                Pattern.compile("物流|快递|配送|发货"),
                Pattern.compile("退货|退款|售后")
        )));
        // 库存/价格查询
        rules.add(new IntentRule(Intent.STOCK_QUERY, List.of(
                Pattern.compile("库存|缺货|有货|补货"),
                Pattern.compile("多少钱|价格|报价|售价|现价"),
                Pattern.compile("几台|几个|几件")
        )));
        // 分类查询
        rules.add(new IntentRule(Intent.CATEGORY_QUERY, List.of(
                Pattern.compile("分类|品类|类目"),
                Pattern.compile("有什么商品|哪些商品|商品列表")
        )));
        // 闲聊（放在业务查询之后——"订单号的格式"是 RAG_QUERY 不是 CHITCHAT）
        rules.add(new IntentRule(Intent.CHITCHAT, List.of(
                Pattern.compile("^(你好|您好|hi|hello|在吗|在不)[!！。. ]*$", Pattern.CASE_INSENSITIVE),
                Pattern.compile("^你是谁|你叫什么|你能做什么|你会什么"),
                Pattern.compile("今天.+时间|现在.+时间|几点了|日期"),
                Pattern.compile("天气怎么样|天气预报"),
                Pattern.compile("^谢谢|多谢|好的|嗯嗯|好哒")
        )));
        // 拒答（敏感词，简单拦截）
        rules.add(new IntentRule(Intent.REFUSE, List.of(
                Pattern.compile("傻逼|草泥马|fuck", Pattern.CASE_INSENSITIVE)
        )));
    }

    /**
     * 识别用户意图。匹配到第一个命中的规则就返回；全没命中返回默认 RAG_QUERY。
     */
    public Intent classify(String question) {
        if (question == null || question.isBlank()) {
            return Intent.RAG_QUERY;
        }
        String q = question.trim();
        for (IntentRule rule : rules) {
            for (Pattern pattern : rule.patterns()) {
                if (pattern.matcher(q).find()) {
                    log.info("意图识别: question=\"{}\" → intent={} (matched pattern={})",
                            truncate(q, 40), rule.intent(), pattern.pattern());
                    return rule.intent();
                }
            }
        }
        return Intent.RAG_QUERY;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 一条意图规则：意图类型 + 一组或关系的正则。 */
    private record IntentRule(Intent intent, List<Pattern> patterns) {}
}
