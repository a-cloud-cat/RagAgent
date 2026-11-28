package com.example.rag.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别评测报告：整体准确率、各类别精确率/召回率/F1、Macro-F1、
 * 三路命中路径的分布与各自准确率，以及全部错分明细。
 */
public class IntentEvalReport {

    /** 评测用例总数 */
    private int total;

    /** 判对条数 */
    private int correct;

    /** 整体准确率 */
    private double accuracy;

    /** 宏平均 F1（6 个意图类别等权平均，不受类别样本数影响） */
    private double macroF1;

    /** 宏平均精确率 */
    private double macroPrecision;

    /** 宏平均召回率 */
    private double macroRecall;

    /** 各类别指标，key 为意图名 */
    private Map<String, ClassStats> perClass = new LinkedHashMap<>();

    /** 各路命中情况，key 为 pattern / embedding / llm / default */
    private Map<String, PathStats> perPath = new LinkedHashMap<>();

    /** 全部错分样本 */
    private List<CaseResult> misclassified = new ArrayList<>();

    /** 全部样本明细 */
    private List<CaseResult> cases = new ArrayList<>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getCorrect() {
        return correct;
    }

    public void setCorrect(int correct) {
        this.correct = correct;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(double accuracy) {
        this.accuracy = accuracy;
    }

    public double getMacroF1() {
        return macroF1;
    }

    public void setMacroF1(double macroF1) {
        this.macroF1 = macroF1;
    }

    public double getMacroPrecision() {
        return macroPrecision;
    }

    public void setMacroPrecision(double macroPrecision) {
        this.macroPrecision = macroPrecision;
    }

    public double getMacroRecall() {
        return macroRecall;
    }

    public void setMacroRecall(double macroRecall) {
        this.macroRecall = macroRecall;
    }

    public Map<String, ClassStats> getPerClass() {
        return perClass;
    }

    public void setPerClass(Map<String, ClassStats> perClass) {
        this.perClass = perClass;
    }

    public Map<String, PathStats> getPerPath() {
        return perPath;
    }

    public void setPerPath(Map<String, PathStats> perPath) {
        this.perPath = perPath;
    }

    public List<CaseResult> getMisclassified() {
        return misclassified;
    }

    public void setMisclassified(List<CaseResult> misclassified) {
        this.misclassified = misclassified;
    }

    public List<CaseResult> getCases() {
        return cases;
    }

    public void setCases(List<CaseResult> cases) {
        this.cases = cases;
    }

    /** 单个意图类别的指标。 */
    public static class ClassStats {

        /** 该类真实样本数 */
        private int support;

        /** 判对条数 */
        private int truePositive;

        /** 被误判成该类的条数 */
        private int falsePositive;

        /** 该类被漏判条数 */
        private int falseNegative;

        private double precision;

        private double recall;

        private double f1;

        public int getSupport() {
            return support;
        }

        public void setSupport(int support) {
            this.support = support;
        }

        public int getTruePositive() {
            return truePositive;
        }

        public void setTruePositive(int truePositive) {
            this.truePositive = truePositive;
        }

        public int getFalsePositive() {
            return falsePositive;
        }

        public void setFalsePositive(int falsePositive) {
            this.falsePositive = falsePositive;
        }

        public int getFalseNegative() {
            return falseNegative;
        }

        public void setFalseNegative(int falseNegative) {
            this.falseNegative = falseNegative;
        }

        public double getPrecision() {
            return precision;
        }

        public void setPrecision(double precision) {
            this.precision = precision;
        }

        public double getRecall() {
            return recall;
        }

        public void setRecall(double recall) {
            this.recall = recall;
        }

        public double getF1() {
            return f1;
        }

        public void setF1(double f1) {
            this.f1 = f1;
        }
    }

    /** 单条命中路径的统计。 */
    public static class PathStats {

        /** 该路径命中条数 */
        private int count;

        /** 该路径判对条数 */
        private int correct;

        /** 该路径准确率 */
        private double accuracy;

        /** 该路径平均耗时（毫秒） */
        private double avgLatencyMs;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public int getCorrect() {
            return correct;
        }

        public void setCorrect(int correct) {
            this.correct = correct;
        }

        public double getAccuracy() {
            return accuracy;
        }

        public void setAccuracy(double accuracy) {
            this.accuracy = accuracy;
        }

        public double getAvgLatencyMs() {
            return avgLatencyMs;
        }

        public void setAvgLatencyMs(double avgLatencyMs) {
            this.avgLatencyMs = avgLatencyMs;
        }
    }

    /** 单题结果。 */
    public static class CaseResult {

        private String question;

        private String expected;

        private String actual;

        /** pattern / embedding / llm / default */
        private String matchedBy;

        private double confidence;

        private double latencyMs;

        private boolean correct;

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getExpected() {
            return expected;
        }

        public void setExpected(String expected) {
            this.expected = expected;
        }

        public String getActual() {
            return actual;
        }

        public void setActual(String actual) {
            this.actual = actual;
        }

        public String getMatchedBy() {
            return matchedBy;
        }

        public void setMatchedBy(String matchedBy) {
            this.matchedBy = matchedBy;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public double getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(double latencyMs) {
            this.latencyMs = latencyMs;
        }

        public boolean isCorrect() {
            return correct;
        }

        public void setCorrect(boolean correct) {
            this.correct = correct;
        }
    }
}
