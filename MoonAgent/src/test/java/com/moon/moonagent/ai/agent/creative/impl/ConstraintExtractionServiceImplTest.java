package com.moon.moonagent.ai.agent.creative.impl;

import com.moon.moonagent.ai.agent.creative.ConstraintLedger;
import com.moon.moonagent.ai.agent.creative.CreativeMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintExtractionServiceImplTest {

    private final ConstraintExtractionServiceImpl service =
            new ConstraintExtractionServiceImpl();

    @Test
    void strictModePreservesHardAndSoftConstraintsSeparately() {
        ConstraintLedger ledger = service.extract(
                "必须保留原来的比例，尺寸必须为16:9；希望整体风格更温暖。");

        assertThat(ledger.mode()).isEqualTo(CreativeMode.STRICT);
        assertThat(ledger.hard()).extracting(ConstraintLedger.ConstraintItem::statement)
                .anyMatch(value -> value.contains("必须保留"));
        assertThat(ledger.soft()).extracting(ConstraintLedger.ConstraintItem::statement)
                .anyMatch(value -> value.contains("希望"));
    }

    @Test
    void strictModeWithoutVerifiableHardConstraintReturnsUnresolved() {
        ConstraintLedger ledger = service.extract("请严格按我的想法做");

        assertThat(ledger.mode()).isEqualTo(CreativeMode.STRICT);
        assertThat(ledger.hard()).isEmpty();
        assertThat(ledger.unresolved()).isNotEmpty();
    }

    @Test
    void balancedRequestDoesNotInventHardConstraints() {
        ConstraintLedger ledger = service.extract("做一张有未来感的城市夜景");

        assertThat(ledger.mode()).isEqualTo(CreativeMode.BALANCED);
        assertThat(ledger.hard()).isEmpty();
        assertThat(ledger.soft()).isEmpty();
    }
}
