package app.mihonsy.komga.data

// SY --> Komiho: 文件夹快捷方式存储。
//
// 「最近」页（来源仪表盘）与「历史」页顶部的快捷方式区共用这一份数据：
// 用户在本地 / WebDAV 浏览页把「当前文件夹」加进来，形成一键直达的入口。
// 数量是个位数，沿用项目既有的「PreferenceStore 单键 JSON 数组」模式（同
// WebDavConnectionStore），不引入 Room。改动通过 [version] 通知 Compose 刷新，
// 避免在界面层层层回传回调。

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

/** 一条文件夹快捷方式。 */
@Serializable
data class BrowseShortcut(
    val id: String,
    val name: String,
    /** [KIND_LOCAL] 或 [KIND_WEBDAV]。 */
    val kind: String,
    /**
     * 目标定位串：
     * - 本地 = 目录的**真实绝对路径**（fs.realPathOf 的结果）；
     * - WebDAV = 目录的**完整 http(s) URL**（baseUrl 之下）。
     */
    val target: String,
    val createdAt: Long = 0L,
) {
    val isLocal: Boolean get() = kind == KIND_LOCAL
    val isWebDav: Boolean get() = kind == KIND_WEBDAV

    companion object {
        const val KIND_LOCAL = "local"
        const val KIND_WEBDAV = "webdav"
    }
}

object BrowseShortcutStore {

    private const val KEY_NAME = "browse_shortcuts_v1"
    /** 快捷方式不宜过多，超出后挤掉最旧的一条。 */
    private const val MAX_SHORTCUTS = 12

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: PreferenceStore by lazy { Injekt.get() }
    private val key = Preference.appStateKey(KEY_NAME)
    private val lock = Any()

    /** 每次增删改自增；界面 collectAsState 后作为 produceState 的 key 触发重算。 */
    val version = MutableStateFlow(0)

    private fun bump() {
        version.value = version.value + 1
    }

    private fun loadLocked(): MutableList<BrowseShortcut> {
        val raw = runBlockingGet()
        return runCatching {
            json.decodeFromString<List<BrowseShortcut>>(raw)
        }.getOrDefault(emptyList()).toMutableList()
    }

    private fun saveLocked(list: List<BrowseShortcut>) {
        val raw = json.encodeToString(list)
        runCatching { prefs.getString(key, "[]").set(raw) }
        bump()
    }

    // PreferenceStore 的 get() 是 suspend；存储层其它 Store 统一用 runBlocking，保持同口径。
    private fun runBlockingGet(): String =
        kotlinx.coroutines.runBlocking { prefs.getString(key, "[]").get() }

    /** 全部快捷方式（最新在前）。 */
    fun all(): List<BrowseShortcut> = synchronized(lock) { loadLocked() }

    /**
     * 新增一条。[target] 相同且同源的视为重复，不重复添加。
     * @return 是否真正新增。
     */
    fun add(name: String, kind: String, target: String): Boolean = synchronized(lock) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return@synchronized false
        val list = loadLocked()
        if (list.any { it.kind == kind && it.target == trimmed }) return@synchronized false
        list.add(
            0,
            BrowseShortcut(
                id = UUID.randomUUID().toString().replace("-", "").take(8),
                name = name.trim().ifBlank { trimmed.substringAfterLast('/') },
                kind = kind,
                target = trimmed,
                createdAt = System.currentTimeMillis(),
            ),
        )
        while (list.size > MAX_SHORTCUTS) list.removeAt(list.lastIndex)
        saveLocked(list)
        true
    }

    fun remove(id: String): Unit = synchronized(lock) {
        val list = loadLocked()
        if (list.removeAll { it.id == id }) saveLocked(list)
    }

    fun clear(): Unit = synchronized(lock) { saveLocked(emptyList()) }
}
// SY <--
