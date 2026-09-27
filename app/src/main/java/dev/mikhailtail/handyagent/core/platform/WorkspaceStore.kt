package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.json.Json

/** 一个 workspace 的元数据。文件本身在 [WorkspaceLayout.dirOf] 下。 */
data class WorkspaceMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastOpenedAt: Long = createdAt,
)

/**
 * workspace 的登记簿。
 *
 * 只存元数据（id / 名字 / 时间），文件内容由 [WorkspaceLayout] 管。
 * 存在 KV 里而不是文件：它很小、读得频繁，且损坏时可以从目录结构重建。
 */
class WorkspaceStore(
    private val kv: KeyValueStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    fun list(): List<WorkspaceMeta> {
        val raw = kv.getString(KEY) ?: return defaultOnly()
        val parsed = runCatching { Json.parse(raw) }.getOrNull() ?: return defaultOnly()
        val items = (parsed as? Json.Arr)?.items.orEmpty().mapNotNull { decode(it) }
        // 默认 workspace 永远存在：老用户升级上来时目录里已经有数据了
        return if (items.none { it.id == WorkspaceLayout.DEFAULT_WORKSPACE_ID }) {
            defaultOnly() + items
        } else {
            items
        }.sortedWith(compareByDescending<WorkspaceMeta> { it.lastOpenedAt })
    }

    fun get(id: String): WorkspaceMeta? = list().firstOrNull { it.id == id }

    fun create(name: String): WorkspaceMeta {
        val at = now()
        // id 由时间戳派生：可读、够唯一，且天然按创建顺序排列
        val meta = WorkspaceMeta(
            id = "w" + at.toString(36) + "-" + (list().size + 1),
            name = name.ifBlank { "工作区 ${list().size + 1}" },
            createdAt = at,
            lastOpenedAt = at,
        )
        save(list() + meta)
        return meta
    }

    fun rename(id: String, name: String): Boolean {
        val all = list()
        val target = all.firstOrNull { it.id == id } ?: return false
        save(all.map { if (it.id == id) target.copy(name = name) else it })
        return true
    }

    /** 删除登记项。**只删元数据**，目录由调用方决定是否清理（避免误删用户的文件）。 */
    fun forget(id: String): Boolean {
        if (id == WorkspaceLayout.DEFAULT_WORKSPACE_ID) return false
        val all = list()
        if (all.none { it.id == id }) return false
        save(all.filterNot { it.id == id })
        return true
    }

    fun touch(id: String) {
        val all = list()
        val target = all.firstOrNull { it.id == id } ?: return
        save(all.map { if (it.id == id) target.copy(lastOpenedAt = now()) else it })
    }

    private fun save(items: List<WorkspaceMeta>) {
        kv.putString(KEY, Json.arr(items.map { encode(it) }).encode())
    }

    private fun defaultOnly(): List<WorkspaceMeta> {
        val at = now()
        return listOf(
            WorkspaceMeta(
                id = WorkspaceLayout.DEFAULT_WORKSPACE_ID,
                name = "默认工作区",
                createdAt = at,
                lastOpenedAt = at,
            )
        )
    }

    private fun encode(m: WorkspaceMeta): Json = Json.obj(
        "id" to Json.of(m.id),
        "name" to Json.of(m.name),
        "createdAt" to Json.of(m.createdAt),
        "lastOpenedAt" to Json.of(m.lastOpenedAt),
    )

    private fun decode(json: Json): WorkspaceMeta? {
        val id = json.str("id") ?: return null
        return WorkspaceMeta(
            id = id,
            name = json.str("name") ?: id,
            createdAt = json.long("createdAt") ?: 0L,
            lastOpenedAt = json.long("lastOpenedAt") ?: 0L,
        )
    }

    companion object {
        const val KEY = "workspaces.index"
    }
}
