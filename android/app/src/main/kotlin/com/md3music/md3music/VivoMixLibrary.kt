package com.md3music.md3music

import android.os.Bundle
import androidx.media3.session.legacy.MediaBrowserCompat
import androidx.media3.session.legacy.MediaDescriptionCompat

/**
 * MD3Music fork v39：vivo 原子随身听（vivomusicmix）的三棵列表树。
 *
 * 协议事实（真机实测 + vivo 侧反编译口径，2026-10-24）：
 * - 岛（com.vivo.musicwidgetmix）先看 metadata 里的能力位
 *   `vivomusicmix.media.metadata.support_event`：我们只声明 31（播控/信息/图/歌词/
 *   进度）时，岛把"播放列表"按钮置灰且**连请求都不发**；声明列表位后，岛会经
 *   MediaBrowserServiceCompat 绑定来取列表（实测日志：external bind
 *   action=android.media.browse.MediaBrowserService）。
 * - 三个父节点：vivomusicmix_current_list / favorite_list / local_list
 *   （对应面板上的"播放列表 / 收藏列表 / 下载列表"三个 tab）。
 * - 分页：请求 options 带 `vivomusicmix_key_media_page`（0 起）；返回项的
 *   description extras 带 `vivomusicmix_key_has_more` 与 `vivomusicmix_key_media_page`
 *   （下一页号）。岛从一页的最后一项读它。
 * - 空列表必须回一条约定 media id（否则岛会一直加载并报网络错误）。
 * - 列表项 media id：`<来源>:<下标>:<标题|歌手 hash>`，岛按 media id 回播。
 *
 * 数据来自 Dart 主动推送（不在 binder 回调里回拉，避免卡住岛的调用线程）。
 */
object VivoMixLibrary {

    /** 列表来源标记（同时是 media id 前缀）。 */
    const val SOURCE_CURRENT = "queue"
    const val SOURCE_FAVORITE = "fav"
    const val SOURCE_LOCAL = "local"

    /** 三个列表父节点（vivo 约定，不可改）。 */
    const val PARENT_CURRENT = "vivomusicmix_current_list"
    const val PARENT_FAVORITE = "vivomusicmix_favorite_list"
    const val PARENT_LOCAL = "vivomusicmix_local_list"

    /** 分页协议键。 */
    const val KEY_PAGE = "vivomusicmix_key_media_page"
    const val KEY_HAS_MORE = "vivomusicmix_key_has_more"

    /** 每页条数（参照实现口径 50）。 */
    const val PAGE_SIZE = 50

    /** 空列表约定的 media id：岛读到它即停止加载（否则一直转圈并报网络错误）。 */
    const val EMPTY_MEDIA_ID = "vivomusicmix:empty"

    /** 一条列表项。 */
    data class Item(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val artworkUri: String?,
        val durationMs: Long,
    )

    @Volatile
    var current: List<Item> = emptyList()

    @Volatile
    var favorites: List<Item> = emptyList()

    @Volatile
    var local: List<Item> = emptyList()

    /** Dart 推送到达时整体替换三棵树的快照。 */
    fun replace(currentItems: List<Item>, favoriteItems: List<Item>, localItems: List<Item>) {
        current = currentItems
        favorites = favoriteItems
        local = localItems
    }

    private fun listOf(parentId: String?): List<Item>? =
        when (parentId) {
            PARENT_CURRENT -> current
            PARENT_FAVORITE -> favorites
            PARENT_LOCAL -> local
            else -> null
        }

    /**
     * 构造一页 children。
     *
     * @param parentId 岛请求的父节点
     * @param options 岛带上来的分页参数（`vivomusicmix_key_media_page`，0 起）
     * @return 该页的 MediaItem；父节点不认识时返回 null（调用方回空列表）
     */
    fun buildChildren(
        parentId: String?,
        options: Bundle?,
    ): List<MediaBrowserCompat.MediaItem>? {
        val items = listOf(parentId) ?: return null
        if (items.isEmpty()) return listOf(emptyItem())
        val page = options?.getInt(KEY_PAGE, 0) ?: 0
        val from = page * PAGE_SIZE
        if (from >= items.size) return listOf(emptyItem())
        val to = minOf(from + PAGE_SIZE, items.size)
        val hasMore = to < items.size
        val out = ArrayList<MediaBrowserCompat.MediaItem>(to - from)
        for (i in from until to) {
            val item = items[i]
            val extras = Bundle().apply {
                putBoolean(KEY_HAS_MORE, hasMore)
                putInt(KEY_PAGE, page + 1)
            }
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(item.mediaId)
                .setTitle(item.title)
                .setSubtitle(item.artist)
                .setDescription(item.album)
                .setExtras(extras)
                .build()
            out.add(MediaBrowserCompat.MediaItem(description, 0))
        }
        return out
    }

    /** 空列表标记项（media id 必须非空，否则 MediaItem 构造直接抛）。 */
    private fun emptyItem(): MediaBrowserCompat.MediaItem {
        val description = MediaDescriptionCompat.Builder()
            .setMediaId(EMPTY_MEDIA_ID)
            .setTitle("")
            .build()
        return MediaBrowserCompat.MediaItem(description, 0)
    }
}
