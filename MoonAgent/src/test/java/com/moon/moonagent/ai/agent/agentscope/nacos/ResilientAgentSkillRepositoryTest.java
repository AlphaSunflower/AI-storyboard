package com.moon.moonagent.ai.agent.agentscope.nacos;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientAgentSkillRepositoryTest {

    @Test
    void usesCompleteNacosSetWhenAllExpectedSkillsLoad() {
        AgentSkill image = skill("image", "from-nacos");
        AgentSkill video = skill("video", "from-nacos");
        FakeRepository nacos = new FakeRepository("nacos", image, video);
        FakeRepository classpath = new FakeRepository(
                "classpath", skill("image", "from-classpath"), skill("video", "from-classpath"));

        ResilientAgentSkillRepository repository = new ResilientAgentSkillRepository(
                List.of("image", "video"), true, () -> nacos, classpath);

        assertThat(repository.getAllSkillNames()).containsExactly("image", "video");
        assertThat(repository.getSkill("image").getDescription()).isEqualTo("from-nacos");
        assertThat(repository.selectedSource()).isEqualTo("nacos");
        assertThat(repository.fallbackReason()).isNull();
    }

    @Test
    void usesOneCompleteClasspathSetWhenNacosIsIncomplete() {
        FakeRepository nacos = new FakeRepository("nacos", skill("image", "from-nacos"));
        FakeRepository classpath = new FakeRepository(
                "classpath", skill("image", "from-classpath"), skill("video", "from-classpath"));

        ResilientAgentSkillRepository repository = new ResilientAgentSkillRepository(
                List.of("image", "video"), true, () -> nacos, classpath);

        assertThat(repository.getAllSkillNames()).containsExactly("image", "video");
        assertThat(repository.getSkill("image").getDescription()).isEqualTo("from-classpath");
        assertThat(repository.getSkill("video").getDescription()).isEqualTo("from-classpath");
        assertThat(repository.selectedSource()).isEqualTo("classpath");
        assertThat(repository.fallbackReason()).contains("missing=[video]");
    }

    @Test
    void fallsBackWhenAiServiceCannotBeCreated() {
        FakeRepository classpath = new FakeRepository(
                "classpath", skill("image", "from-classpath"), skill("video", "from-classpath"));
        ResilientAgentSkillRepository repository = new ResilientAgentSkillRepository(
                List.of("image", "video"),
                true,
                () -> {
                    throw new IllegalStateException("Nacos unavailable");
                },
                classpath);

        assertThat(repository.getAllSkills())
                .extracting(AgentSkill::getName)
                .containsExactly("image", "video");
        assertThat(repository.selectedSource()).isEqualTo("classpath");
        assertThat(repository.fallbackReason()).isEqualTo("Nacos unavailable");
    }

    @Test
    void failsClosedWhenFallbackIsDisabled() {
        FakeRepository classpath = new FakeRepository("classpath", skill("image", "fallback"));
        ResilientAgentSkillRepository repository = new ResilientAgentSkillRepository(
                List.of("image"),
                false,
                () -> null,
                classpath);

        assertThatThrownBy(repository::getAllSkills)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath fallback is disabled");
    }

    private static AgentSkill skill(String name, String description) {
        return new AgentSkill(name, description, "# " + name, Map.of());
    }

    private static final class FakeRepository implements AgentSkillRepository {

        private final String source;
        private final Map<String, AgentSkill> skills;

        private FakeRepository(String source, AgentSkill... skills) {
            this.source = source;
            this.skills = new LinkedHashMap<>();
            for (AgentSkill skill : skills) {
                this.skills.put(skill.getName(), skill);
            }
        }

        @Override
        public AgentSkill getSkill(String skillName) {
            AgentSkill skill = skills.get(skillName);
            if (skill == null) {
                throw new IllegalArgumentException("Missing skill " + skillName);
            }
            return skill;
        }

        @Override
        public List<String> getAllSkillNames() {
            return List.copyOf(skills.keySet());
        }

        @Override
        public List<AgentSkill> getAllSkills() {
            return List.copyOf(skills.values());
        }

        @Override
        public boolean save(List<AgentSkill> skills, boolean force) {
            return false;
        }

        @Override
        public boolean delete(String skillName) {
            return false;
        }

        @Override
        public boolean skillExists(String skillName) {
            return skills.containsKey(skillName);
        }

        @Override
        public AgentSkillRepositoryInfo getRepositoryInfo() {
            return new AgentSkillRepositoryInfo("test", source, false);
        }

        @Override
        public String getSource() {
            return source;
        }

        @Override
        public void setWriteable(boolean writeable) {
        }

        @Override
        public boolean isWriteable() {
            return false;
        }
    }
}
