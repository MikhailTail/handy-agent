package dev.mikhailtail.handyagent.persistence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSONL 转录里的一行。
 *
 * **为什么大部分字段可空**：转录格式随 cc-haha 版本演进，不同 `type` 的行带完全不同的字段
 * （见下）。解析时必须宽松（`ignoreUnknownKeys` + 全字段可空），否则一次上游升级就会让
 * 整个历史读不出来 —— 而这些数据是**用户自己的**，读不动等于丢数据。
 *
 * `type` 的实测取值（来自本机 `cc-haha/projects` 下的真实转录）：
 * ```
 * user                     对话消息（可能是用户输入，也可能是 tool_result 的载体）
 * assistant                助手消息（含 text / thinking / tool_use 三类 block）
 * session-meta             会话元信息：workDir / permissionMode / 运行时的 provider 与 model
 * ai-title                  AI 生成的会话标题
 * queue-operation          队列操作记录
 * file-history-snapshot    文件历史快照
 * ```
 *
 * 只有 `user` / `assistant` 会变成消息；其余是给列表与元数据用的。
 */
@Serializable
data class TranscriptLine(
    val type: String,
    val uuid: String? = null,
    val parentUuid: String? = null,
    val timestamp: String? = null,
    val sessionId: String? = null,
    val cwd: String? = null,
    val version: String? = null,
    val gitBranch: String? = null,
    val isSidechain: Boolean? = null,

    /** 仅模型可见的条目（例如系统注入的上下文），不该出现在用户界面上。 */
    @SerialName("isMeta") val isMeta: Boolean? = null,

    /** 原始 Anthropic 消息体：`{ role, content: [...] }`。结构多变，原样保留避免失真。 */
    val message: JsonElement? = null,

    // ── session-meta ──────────────────────────────────────────────────────
    val workDir: String? = null,
    val permissionMode: String? = null,
    val runtimeProviderId: String? = null,
    val runtimeModelId: String? = null,
    val effortLevel: String? = null,

    // ── ai-title ──────────────────────────────────────────────────────────
    val aiTitle: String? = null,

    // ── system（压缩边界等）───────────────────────────────────────────────
    val subtype: String? = null,
    val text: String? = null,
    val tokensBefore: Int? = null,
    val tokensAfter: Int? = null,
)

/**
 * 会话元信息 —— 由 `session-meta` 行累积而来。
 *
 * 一个会话可能出现多条 `session-meta`（用户中途改了权限模式或换了模型），
 * 按时间顺序取**最后一条**即为当前状态。
 */
data class SessionMeta(
    val workDir: String? = null,
    val permissionMode: String? = null,
    val runtimeProviderId: String? = null,
    val runtimeModelId: String? = null,
    val effortLevel: String? = null,
)
