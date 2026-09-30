package com.moon.moonagent.ai.agent.creative.impl;

import com.moon.moonagent.ai.agent.creative.ConstraintExtractionService;
import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.ConstraintSeverity;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConstraintExtractionServiceImpl implements ConstraintExtractionService {

    private static final Pattern NUMBER = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*(?:秒|分钟|个|张|条|幅|套|%|％|px|像素|k|K)?)");
    private static final List<String> HARD_MARKERS = List.of(
            "必须", "忠于", "保留", "不能", "不得", "禁止",
            "一致", "固定", "只允许", "只能", "确保", "原样"
    );
    private static final List<String> SOFT_MARKERS = List.of(
            "希望", "想要", "倾向", "建议", "最好", "可以", "尽量", "偏好", "风格像"
    );
    private static final List<String> REFERENCE_MARKERS = List.of(
            "参考", "参照", "沿用", "同一", "复用", "保持与前文"
    );

    @Override
    public ConstraintLedger extract(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        CreativeMode mode = CreativeMode.detect(text);
        if (text.isBlank()) {
            return new ConstraintLedger(mode, List.of(), List.of(), List.of());
        }

        List<ConstraintLedger.ConstraintItem> hard = new ArrayList<>();
        List<ConstraintLedger.ConstraintItem> soft = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String sentence : sentences(text)) {
            String normalized = normalize(sentence);
            if (normalized.length() < 2 || seen.putIfAbsent(normalized, Boolean.TRUE) != null) continue;
            String lower = normalized.toLowerCase(Locale.ROOT);
            boolean hardMatch = containsAny(lower, HARD_MARKERS);
            boolean softMatch = containsAny(lower, SOFT_MARKERS);
            if (!hardMatch && !softMatch) continue;
            ConstraintLedger.ConstraintItem item = item(
                    normalized, hardMatch ? ConstraintSeverity.HARD : ConstraintSeverity.SOFT);
            if (item.severity() == ConstraintSeverity.HARD) hard.add(item);
            else soft.add(item);
        }

        List<String> unresolved = new ArrayList<>();
        if (mode == CreativeMode.STRICT && hard.isEmpty()) {
            unresolved.add("用户要求严格模式，但没有识别到可验证的硬约束；执行前需要确认最不可更改的对象。");
        }
        return new ConstraintLedger(mode, hard, soft, unresolved);
    }

    private static ConstraintLedger.ConstraintItem item(String statement,
                                                        ConstraintSeverity severity) {
        String value = numbers(statement);
        String verifier = !value.isBlank()
                ? "numeric"
                : containsAny(statement.toLowerCase(Locale.ROOT), REFERENCE_MARKERS)
                ? "reference"
                : "llm";
        String id = UUID.nameUUIDFromBytes(statement.getBytes(StandardCharsets.UTF_8)).toString();
        return new ConstraintLedger.ConstraintItem(
                id, statement, severity, "user", verifier, value);
    }

    private static List<String> sentences(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<>();
        for (String line : normalized.split("\\n+")) {
            for (String sentence : line.split("(?<=[。！？!?；;])|(?<=，)(?=.*(?:必须|严格|不能|不得|保留|希望|想要))")) {
                String value = sentence.trim();
                if (!value.isBlank()) result.add(value);
            }
        }
        return result;
    }

    private static String numbers(String text) {
        Matcher matcher = NUMBER.matcher(text);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isBlank() && !values.contains(value)) values.add(value);
        }
        return String.join(" | ", values);
    }

    private static boolean containsAny(String value, List<String> needles) {
        return needles.stream().anyMatch(value::contains);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
