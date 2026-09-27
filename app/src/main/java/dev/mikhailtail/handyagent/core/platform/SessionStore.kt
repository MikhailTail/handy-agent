package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.Message
import dev.mikhailtail.handyagent.core.model.MessageBoundaries
import dev.mikhailtail.handyagent.core.model.MessageCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/** 会话列表项。列表页只读它，不必解析整个会话文件。 */
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    /** 最后一条消息的短摘要，用于列表展示。 */
    val preview: String = "",
    val providerId: String? = null,
    val model: String? = null,
    /** Fork 来源；非 Fork 会话为 null。 */
    val parentId: String? = null,
    val forkedAtMessage: Int? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** 供搜索用的文本摘要，避免每次搜索都解析整个会话。 */
    val searchBlob: String = "",
)

data class SessionRecord(val meta: SessionMeta, val messages: List<Message>)

data class SessionHit(val meta: SessionMeta, val snippet: String)

interface SessionStore {
    fun list(includeArchived: Boolean = false): List<SessionMeta>
    fun load(id: String): SessionRecord?
    fun create(title: String = "", providerId: String? = null, model: String? = null): SessionRecord
    fun save(record: SessionRecord)
    fun append(id: String, extra: List<Message>): SessionRecord?

    /**
     * 用权威历史整体替换一个会话的内容。
     *
     * 一轮结束后 `AgentLoop.history()` 才是唯一事实来源（它会在中断时补齐 tool_result，
     * 保证历史合法），因此增量追加不如结束时整体写回可靠。
     */
    fun replaceMessages(id: String, messages: List<Message>): Boolean
    fun delete(id: String): Boolean
    fun rename(id: String, title: String): Boolean
    fun setPinned(id: String, pinned: Boolean): Boolean

    /** 从 [uptoMessageIndex] 处复制出一个新会话；默认复制全部消息。 */
    fun fork(id: String, uptoMessageIndex: Int? = null, title: String = ""): SessionRecord?
    fun search(query: String, limit: Int = 50): List<SessionHit>
}

/**
 * 文件系统实现：一个会话一个 JSON，外加一份只读列表用的索引。
 *
 * 三条工程约束（都是为了避免「会话打不开」这类最难查的问题）：
 * 1. **原子写**：先写 `.tmp` 再 rename，绝不留下写了一半的文件。
 * 2. **索引可重建**：index.json 丢了或坏了就扫目录重建，不让索引成为单点故障。
 * 3. **读不抛异常**：单个会话文件损坏只跳过它，其余会话照常可用。
 *
 * 只用 `java.io` + 自研 Json，因此可以在宿主 JVM 上完整测试。
 */
class FileSessionStore(
    private val root: File,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { randomId() },
) : SessionStore {

    private val indexFile: File get() = File(root, INDEX_FILE)

    init {
        runCatching { root.mkdirs() }
    }

    // ------------------------------------------------------------------ list

    @Synchronized
    override fun list(includeArchived: Boolean): List<SessionMeta> {
        val index = readIndex() ?: rebuildIndex()
        return index
            .filter { includeArchived || !it.archived }
            .sortedWith(compareByDescending<SessionMeta> { it.pinned }.thenByDescending { it.updatedAt })
    }

    // ------------------------------------------------------------------ load

    @Synchronized
    override fun load(id: String): SessionRecord? {
        if (!safeId(id)) return null
        val f = File(root, "$id.json")
        if (!f.isFile) return null
        return runCatching {
            val doc = Json.parse(f.readText())
            val meta = decodeMeta(doc.obj("meta")) ?: return null
            val messages = MessageCodec.decodeAll(doc["messages"] ?: Json.arr(emptyList()))
            SessionRecord(meta, messages)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- create

    @Synchronized
    override fun create(title: String, providerId: String?, model: String?): SessionRecord {
        val at = now()
        val meta = SessionMeta(
            id = newId(),
            title = title.ifBlank { DEFAULT_TITLE },
            createdAt = at,
            updatedAt = at,
            messageCount = 0,
            providerId = providerId,
            model = model,
        )
        val record = SessionRecord(meta, emptyList())
        save(record)
        return record
    }

    // ------------------------------------------------------------------ save

    @Synchronized
    override fun save(record: SessionRecord) {
        if (!safeId(record.meta.id)) return
        val meta = record.meta.copy(
            messageCount = record.messages.size,
            preview = previewOf(record.messages),
            searchBlob = searchBlobOf(record.messages),
        )
        val doc = Json.obj(
            "v" to Json.of(FORMAT_VERSION),
            "meta" to encodeMeta(meta),
            "messages" to MessageCodec.encodeAll(record.messages),
        )
        if (writeAtomic(File(root, "${meta.id}.json"), doc.encode())) {
            upsertIndex(meta)
        }
    }

    @Synchronized
    override fun append(id: String, extra: List<Message>): SessionRecord? {
        if (extra.isEmpty()) return load(id)
        val existing = load(id) ?: return null
        val merged = existing.messages + extra
        val updated = existing.copy(
            meta = existing.meta.copy(updatedAt = now()),
            messages = merged,
        )
        save(updated)
        return updated
    }

    // ---------------------------------------------------------------- mutate

    @Synchronized
    override fun replaceMessages(id: String, messages: List<Message>): Boolean {
        val existing = load(id) ?: return false
        save(existing.copy(meta = existing.meta.copy(updatedAt = now()), messages = messages))
        return true
    }

    @Synchronized
    override fun delete(id: String): Boolean {
        if (!safeId(id)) return false
        val removed = runCatching { File(root, "$id.json").delete() }.getOrDefault(false)
        if (removed) {
            val index = readIndex() ?: rebuildIndex()
            writeIndex(index.filterNot { it.id == id })
        }
        return removed
    }

    @Synchronized
    override fun rename(id: String, title: String): Boolean =
        mutateMeta(id) { it.copy(title = title, updatedAt = now()) }

    @Synchronized
    override fun setPinned(id: String, pinned: Boolean): Boolean =
        mutateMeta(id) { it.copy(pinned = pinned) }

    private fun mutateMeta(id: String, transform: (SessionMeta) -> SessionMeta): Boolean {
        val record = load(id) ?: return false
        save(record.copy(meta = transform(record.meta)))
        return true
    }

    // ------------------------------------------------------------------ fork

    @Synchronized
    override fun fork(id: String, uptoMessageIndex: Int?, title: String): SessionRecord? {
        val source = load(id) ?: return null
        val requested = uptoMessageIndex ?: source.messages.size
        // 吸附到合法边界，避免把 tool_use 与其 tool_result 拆到两个会话里。
        val cut = MessageBoundaries.snapToValidCut(source.messages, requested)
        val at = now()
        val meta = source.meta.copy(
            id = newId(),
            title = title.ifBlank { "${source.meta.title} (fork)" },
            createdAt = at,
            updatedAt = at,
            parentId = source.meta.id,
            forkedAtMessage = cut,
            pinned = false,
        )
        val record = SessionRecord(meta, source.messages.take(cut))
        save(record)
        return record
    }

    // ---------------------------------------------------------------- search

    @Synchronized
    override fun search(query: String, limit: Int): List<SessionHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val hits = ArrayList<SessionHit>()
        for (meta in list(includeArchived = true)) {
            if (hits.size >= limit) break
            // 先用索引里的摘要粗筛，命中再解析整个会话取上下文。
            if (!meta.searchBlob.lowercase().contains(q) && !meta.title.lowercase().contains(q)) continue
            val record = load(meta.id) ?: continue
            val snippet = record.messages
                .asSequence()
                .map { it.text }
                .firstOrNull { it.lowercase().contains(q) }
                ?.let { snippetOf(it, q) }
                ?: meta.preview
            hits.add(SessionHit(meta, snippet))
        }
        return hits
    }

    // ----------------------------------------------------------------- index

    /** 返回 null 表示索引缺失或损坏，调用方应当重建。 */
    private fun readIndex(): List<SessionMeta>? = runCatching {
        if (!indexFile.isFile) return null
        val arr = Json.parse(indexFile.readText())
        (arr as? Json.Arr)?.items?.mapNotNull { decodeMeta(it) }
    }.getOrNull()

    private fun writeIndex(index: List<SessionMeta>): Boolean =
        writeAtomic(indexFile, Json.arr(index.map { encodeMeta(it) }).encode())

    /** 扫目录重建索引；顺带丢弃读不出来的会话。 */
    private fun rebuildIndex(): List<SessionMeta> {
        val metas = root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") && it.name != INDEX_FILE }
            ?.mapNotNull { f ->
                runCatching {
                    val doc = Json.parse(f.readText())
                    decodeMeta(doc.obj("meta"))
                }.getOrNull()
            }
            ?: emptyList()
        writeIndex(metas)
        return metas
    }

    private fun upsertIndex(meta: SessionMeta) {
        val index = readIndex() ?: rebuildIndex()
        writeIndex(index.filterNot { it.id == meta.id } + meta)
    }

    private fun encodeMeta(m: SessionMeta): Json = Json.obj(
        "id" to Json.of(m.id),
        "title" to Json.of(m.title),
        "createdAt" to Json.of(m.createdAt),
        "updatedAt" to Json.of(m.updatedAt),
        "messageCount" to Json.of(m.messageCount),
        "preview" to Json.of(m.preview),
        "providerId" to Json.of(m.providerId),
        "model" to Json.of(m.model),
        "parentId" to Json.of(m.parentId),
        "forkedAtMessage" to Json.of(m.forkedAtMessage ?: -1),
        "pinned" to Json.of(m.pinned),
        "archived" to Json.of(m.archived),
        "searchBlob" to Json.of(m.searchBlob),
    )

    private fun decodeMeta(json: Json?): SessionMeta? {
        if (json == null) return null
        val id = json.str("id") ?: return null
        return SessionMeta(
            id = id,
            title = json.str("title") ?: DEFAULT_TITLE,
            createdAt = json.long("createdAt") ?: 0L,
            updatedAt = json.long("updatedAt") ?: 0L,
            messageCount = json.int("messageCount") ?: 0,
            preview = json.str("preview").orEmpty(),
            providerId = json.str("providerId"),
            model = json.str("model"),
            parentId = json.str("parentId"),
            forkedAtMessage = json.int("forkedAtMessage")?.takeIf { it >= 0 },
            pinned = json.bool("pinned") ?: false,
            archived = json.bool("archived") ?: false,
            searchBlob = json.str("searchBlob").orEmpty(),
        )
    }

    // ----------------------------------------------------------------- utils

    /**
     * 原子写：先写 .tmp 再移动覆盖。
     *
     * 必须用 [Files.move] 而不是 [File.renameTo]：后者在 **Windows 上无法覆盖已存在的
     * 目标文件**（Unix 可以），会让「第二次保存」静默失败 —— 会话改名、追加消息、
     * 更新索引全部写不进去，而且不报任何错。
     */
    private fun writeAtomic(target: File, text: String): Boolean {
        val tmp = File(root, "${target.name}.tmp")
        if (!runCatching { tmp.writeText(text) }.isSuccess) return false
        // 优先原子替换；文件系统不支持时退化为普通覆盖移动。
        val atomic = runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
        if (atomic) return true
        val ok = runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!ok) runCatching { tmp.delete() }
        return ok
    }

    private fun previewOf(messages: List<Message>): String =
        messages.lastOrNull { it.text.isNotBlank() }?.text?.replace('\n', ' ')?.take(PREVIEW_CHARS).orEmpty()

    /** 搜索摘要只取前若干条消息的文本，避免索引随会话无限膨胀。 */
    private fun searchBlobOf(messages: List<Message>): String =
        messages.asSequence()
            .map { it.text }
            .filter { it.isNotBlank() }
            .take(BLOB_MESSAGES)
            .joinToString(" ")
            .take(BLOB_CHARS)

    private fun snippetOf(text: String, lowerQuery: String): String {
        val at = text.lowercase().indexOf(lowerQuery)
        if (at < 0) return text.take(SNIPPET_CHARS)
        val start = (at - SNIPPET_PAD).coerceAtLeast(0)
        val end = (at + lowerQuery.length + SNIPPET_PAD).coerceAtMost(text.length)
        val head = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        return head + text.substring(start, end).replace('\n', ' ') + tail
    }

    private fun safeId(id: String): Boolean =
        id.isNotBlank() && !id.contains('/') && !id.contains('\\') && !id.contains("..")

    companion object {
        const val INDEX_FILE = "index.json"
        const val DEFAULT_TITLE = "新会话"
        const val FORMAT_VERSION = 1

        private const val PREVIEW_CHARS = 120
        private const val BLOB_MESSAGES = 12
        private const val BLOB_CHARS = 4_000
        private const val SNIPPET_CHARS = 160
        private const val SNIPPET_PAD = 60

        private fun randomId(): String =
            "s" + Random.nextLong(1L shl 40).toString(36)
    }
}
