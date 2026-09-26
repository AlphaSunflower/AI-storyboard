package com.moon.moonagent.ai.agent.agentscope.nacos;

import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.skill.NacosSkillRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NacosSkillVersionSelectionTest {

    @Test
    void configuredVersionWinsOverLabel() throws Exception {
        AiService aiService = mock(AiService.class);
        byte[] archive = skillZip(
                "image",
                "---\nname: image\ndescription: image skill\nversion: \"3.0.0\"\n---\n\n# Image\n");
        when(aiService.downloadSkillZipByVersion("image", "3.0.0")).thenReturn(archive);

        Properties properties = new Properties();
        properties.setProperty(NacosSkillRepository.SKILL_VERSION_PATH, "3.0.0");
        properties.setProperty(NacosSkillRepository.SKILL_LABEL_PATH, "stable");
        NacosSkillRepository repository = new NacosSkillRepository(
                aiService,
                "public",
                properties,
                List.of("image"));

        assertThat(repository.getSkill("image").getName()).isEqualTo("image");
        verify(aiService).downloadSkillZipByVersion("image", "3.0.0");
        verify(aiService, never()).downloadSkillZipByLabel("image", "stable");
        verify(aiService, never()).downloadSkillZip("image");
    }

    private static byte[] skillZip(String name, String skillMd) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            archive.write(skillMd.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return output.toByteArray();
    }
}
