package com.moon.moonagent.ai.agent.agentscope;

import com.moon.moonagent.ai.agent.sandbox.MoonSandboxClient;
import com.moon.moonagent.ai.agent.sandbox.SandboxSkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * ppt-master 技能的按需启用器。
 *
 * <p>这个技能默认**不**出现在模型的 {@code <available_skills>} 里，也**不**会占用任何会话工作区。
 * 只有满足下面任一条件时，才会把 {@value #SKILL_ID} 加入本轮技能白名单，并让 MoonSandbox
 * 把只读技能包复制到 {@code /workspaces/{conversationId}/skills/ppt-master}：
 *
 * <ul>
 *   <li>本轮用户消息显式引用了它：{@code /ppt} 菜单写入的 {@code ppt-master} 字样，或直接提到
 *       {@code ppt-master}；</li>
 *   <li>本轮是普通的 PPT 需求：正文出现 PPT/PPTX/幻灯片/演示文稿/课件。</li>
 * </ul>
 *
 * <p>一旦某个会话引用过，后续回合继续可用（HITL 续流、编辑重发不会因为正文变成「确认」而丢掉技能）。
 * 从未引用过的会话不会产生 {@code skills/} 目录，也不会看到该技能。
 */
@Component
public class PptMasterSkillSupport {

    private static final Logger log = LoggerFactory.getLogger(PptMasterSkillSupport.class);

    public static final String SKILL_ID = "ppt-master";

    /** 显式引用：ppt-master / ppt master / [[skill:ppt-master]]。 */
    private static final Pattern EXPLICIT_REFERENCE =
            Pattern.compile("(?i)(ppt[-_ ]?master|\\[\\[skill:ppt-master\\]\\])");

    /** 自然语言里的 PPT 需求。英文用词边界，避免误伤 appointment 之类的词。 */
    private static final Pattern PPT_REQUEST =
            Pattern.compile("(?i)(?<![a-z])pptx?(?![a-z])|幻灯片|演示文稿|演示稿|课件");

    private static final String SKILL_ENTRY = "skills/" + SKILL_ID + "/SKILL.md";

    private final MoonSandboxClient sandboxClient;

    /** 已确认物化成功的会话；进程重启后由 SKILL.md 探测兜底。 */
    private final Set<String> installed = ConcurrentHashMap.newKeySet();
    /** 已确认没有该技能的会话，避免每个普通回合都打一次沙箱读请求。 */
    private final Set<String> absent = ConcurrentHashMap.newKeySet();

    @Autowired
    public PptMasterSkillSupport(MoonSandboxClient sandboxClient) {
        this.sandboxClient = sandboxClient;
    }

    /** 本轮消息是否引用了 ppt-master（显式或自然语言 PPT 需求）。 */
    public static boolean isReferenced(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        return EXPLICIT_REFERENCE.matcher(content).find()
                || PPT_REQUEST.matcher(content).find();
    }

    /**
     * 计算本轮要额外挂载的技能。
     *
     * <p>返回空集 = 不挂载：模型看不到该技能，沙箱里也不会出现技能目录。
     * 只在物化成功（或此前已物化）时返回 {@code ppt-master}；物化失败宁可本轮不启用，
     * 也不让模型在没有脚本/模板的情况下照着一份读不到的说明空跑。
     */
    public Set<String> skillsForTurn(String conversationId, String content) {
        if (conversationId == null || conversationId.isBlank()) {
            return Set.of();
        }
        if (sandboxClient == null || !sandboxClient.enabled()) {
            return Set.of();
        }
        if (!isReferenced(content) && !isInstalled(conversationId)) {
            return Set.of();
        }
        return ensureMaterialized(conversationId) ? Set.of(SKILL_ID) : Set.of();
    }

    /** 该会话是否已经引用过 ppt-master（技能目录已物化）。 */
    public boolean isInstalled(String conversationId) {
        if (installed.contains(conversationId)) {
            return true;
        }
        if (absent.contains(conversationId)) {
            return false;
        }
        try {
            sandboxClient.read(conversationId, SKILL_ENTRY, 0, 1);
            installed.add(conversationId);
            return true;
        } catch (RuntimeException notInstalled) {
            absent.add(conversationId);
            return false;
        }
    }

    private boolean ensureMaterialized(String conversationId) {
        if (installed.contains(conversationId)) {
            return true;
        }
        try {
            SandboxSkillResult result = sandboxClient.materializeSkill(conversationId, SKILL_ID);
            installed.add(conversationId);
            absent.remove(conversationId);
            log.info("[PptMaster] skill {} for conversation {} path={} installed={} files={}",
                    SKILL_ID, conversationId,
                    result == null ? "" : result.path(),
                    result != null && result.installed(),
                    result == null ? 0 : result.files());
            return true;
        } catch (RuntimeException error) {
            absent.add(conversationId);
            log.warn("[PptMaster] skill materialization failed for conversation {}: {}",
                    conversationId, error.getMessage());
            return false;
        }
    }
}
