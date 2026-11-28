package com.example.rag.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.example.rag.eval.IntentEvalReport.CaseResult;
import com.example.rag.eval.IntentEvalReport.ClassStats;
import com.example.rag.eval.IntentEvalReport.PathStats;
import com.example.rag.service.intent.Intent;
import com.example.rag.service.intent.IntentResult;
import com.example.rag.service.intent.IntentRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 意图识别评测服务：加载标注用例，逐题复用 IntentRouter 主链路做分类，
 * 统计准确率、各类别 P/R/F1、Macro-F1，以及三路命中路径的分布与各自准确率。
 */
@Service
public class IntentEvalService {

    private static final Logger log = LoggerFactory.getLogger(IntentEvalService.class);

    private static final String CASES_RESOURCE = "eval/intent-cases.json";

    private final ObjectMapper objectMapper;

    private final IntentRouter intentRouter;

    public IntentEvalService(ObjectMapper objectMapper, IntentRouter intentRouter) {
        this.objectMapper = objectMapper;
        this.intentRouter = intentRouter;
    }

    /**
     * 跑一遍意图识别评测。
     *
     * @return 汇总报告，含每题明细与错分明细
     */
    public IntentEvalReport run() {
        List<IntentEvalCase> cases = loadCases();
        int total = cases.size();

        List<CaseResult> results = new ArrayList<>(total);
        List<CaseResult> misclassified = new ArrayList<>();
        int correct = 0;

        // 各类别的 TP / FP / FN 计数器，key 为意图名
        Map<String, int[]> counters = new LinkedHashMap<>();
        for (Intent intent : Intent.values()) {
            counters.put(intent.name(), new int[]{0, 0, 0}); // TP, FP, FN
        }
        // 各路命中计数与耗时累加，key 为 pattern / embedding / llm / default
        Map<String, PathStats> pathStats = new LinkedHashMap<>();
        Map<String, Double> pathLatencySum = new LinkedHashMap<>();

        for (IntentEvalCase evalCase : cases) {
            long start = System.nanoTime();
            IntentResult result = intentRouter.classify(evalCase.getQuestion());
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;

            String expected = evalCase.getExpectedIntent();
            String actual = result.intent().name();
            boolean hit = expected.equals(actual);
            if (hit) {
                correct++;
            }

            // 混淆计数：判对记 TP；判错时，期望类记 FN，实际类记 FP
            int[] expectedCounter = counters.get(expected);
            int[] actualCounter = counters.get(actual);
            if (expectedCounter != null && actualCounter != null) {
                if (hit) {
                    expectedCounter[0]++;
                } else {
                    expectedCounter[2]++;
                    actualCounter[1]++;
                }
            }

            // 路径统计
            String path = result.matchedBy() == null ? "default" : result.matchedBy();
            PathStats ps = pathStats.computeIfAbsent(path, k -> new PathStats());
            ps.setCount(ps.getCount() + 1);
            if (hit) {
                ps.setCorrect(ps.getCorrect() + 1);
            }
            pathLatencySum.merge(path, latencyMs, Double::sum);

            CaseResult cr = new CaseResult();
            cr.setQuestion(evalCase.getQuestion());
            cr.setExpected(expected);
            cr.setActual(actual);
            cr.setMatchedBy(path);
            cr.setConfidence(round3(result.confidence()));
            cr.setLatencyMs(round3(latencyMs));
            cr.setCorrect(hit);
            results.add(cr);
            if (!hit) {
                misclassified.add(cr);
            }
        }

        // 组装各类别指标
        Map<String, ClassStats> perClass = new LinkedHashMap<>();
        double f1Sum = 0;
        double precisionSum = 0;
        double recallSum = 0;
        int classCount = 0;
        for (Map.Entry<String, int[]> entry : counters.entrySet()) {
            int[] c = entry.getValue();
            int tp = c[0];
            int fp = c[1];
            int fn = c[2];
            int support = tp + fn;

            ClassStats stats = new ClassStats();
            stats.setSupport(support);
            stats.setTruePositive(tp);
            stats.setFalsePositive(fp);
            stats.setFalseNegative(fn);
            stats.setPrecision(div(tp, tp + fp));
            stats.setRecall(div(tp, tp + fn));
            stats.setF1(f1(stats.getPrecision(), stats.getRecall()));
            perClass.put(entry.getKey(), stats);

            // 宏平均只统计有样本的类别，避免空类别拉低均值
            if (support > 0) {
                precisionSum += stats.getPrecision();
                recallSum += stats.getRecall();
                f1Sum += stats.getF1();
                classCount++;
            }
        }

        // 组装各路指标
        for (Map.Entry<String, PathStats> entry : pathStats.entrySet()) {
            PathStats ps = entry.getValue();
            ps.setAccuracy(div(ps.getCorrect(), ps.getCount()));
            Double sum = pathLatencySum.get(entry.getKey());
            ps.setAvgLatencyMs(sum == null ? 0 : round3(sum / ps.getCount()));
        }

        IntentEvalReport report = new IntentEvalReport();
        report.setTotal(total);
        report.setCorrect(correct);
        report.setAccuracy(div(correct, total));
        report.setMacroPrecision(classCount == 0 ? 0 : round3(precisionSum / classCount));
        report.setMacroRecall(classCount == 0 ? 0 : round3(recallSum / classCount));
        report.setMacroF1(classCount == 0 ? 0 : round3(f1Sum / classCount));
        report.setPerClass(perClass);
        report.setPerPath(pathStats);
        report.setMisclassified(misclassified);
        report.setCases(results);

        log.info("意图识别评测完成：{}/{} 正确，accuracy={}，macroF1={}",
                correct, total, String.format("%.3f", report.getAccuracy()),
                String.format("%.3f", report.getMacroF1()));
        return report;
    }

    /** F1 = 2PR/(P+R)，P+R 为 0 时返回 0。 */
    private double f1(double precision, double recall) {
        if (precision + recall == 0) {
            return 0;
        }
        return round3(2 * precision * recall / (precision + recall));
    }

    /** 安全除法，分母为 0 返回 0，并保留三位小数。 */
    private double div(int numerator, int denominator) {
        return denominator == 0 ? 0 : round3((double) numerator / denominator);
    }

    /** 保留三位小数。 */
    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** 从 classpath 读取并解析意图评测集。 */
    private List<IntentEvalCase> loadCases() {
        try (InputStream in = new ClassPathResource(CASES_RESOURCE).getInputStream()) {
            List<IntentEvalCase> cases = objectMapper.readValue(in, new TypeReference<List<IntentEvalCase>>() {
            });
            for (int i = 0; i < cases.size(); i++) {
                IntentEvalCase c = cases.get(i);
                if (c.getQuestion() == null || c.getQuestion().isBlank()
                        || c.getExpectedIntent() == null || c.getExpectedIntent().isBlank()) {
                    throw new IllegalStateException(
                            "意图评测集第 " + (i + 1) + " 题缺少 question 或 expectedIntent");
                }
                // 提前校验标注合法性，避免跑完才发现类型拼错
                Intent.valueOf(c.getExpectedIntent());
            }
            return cases;
        } catch (IOException e) {
            throw new IllegalStateException("意图评测集加载失败：" + CASES_RESOURCE, e);
        }
    }
}
