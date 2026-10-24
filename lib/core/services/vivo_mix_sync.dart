import 'dart:async';

import '../../data/models/song.dart';
import '../../providers/favorites_provider.dart';
import '../../providers/library_provider.dart';
import '../../providers/player_provider.dart';
import 'media_notification_service.dart';

/// vivo 原子随身听（vivomusicmix）列表快照推送与点播回调。
///
/// 岛（com.vivo.musicwidgetmix）在能力位放行后，会经 MediaBrowserServiceCompat
/// 绑定取三棵列表树（播放列表 / 收藏列表 / 下载列表）。原生侧只读内存快照，
/// 由本类把 Dart 侧三份列表（去抖 + 内容指纹去重 + 条数上限）推送过去；
/// 同时承接岛端点播回调，按 `<来源>:<songId>` 稳定寻址回播。
///
/// v40 修复（第六轮独立审查）：
/// - attach 改为"换绑"语义：重复 attach 会先摘旧监听再绑新 provider，引擎重建
///   （AppViewState 重建窗口）后不再推送陈旧数据，也不阻止旧 provider 回收；
/// - 补 dispose 路径（去抖 Timer 取消、监听摘除、点播回调置空）；
/// - 内容指纹去重：三棵树的 id 串没变就不推（对齐 FmWidgetSync 的闸门）；
/// - 本地列表截断（[_localCap]）：设备级媒体库可达数千首，全量推送会把 UI
///   isolate 与原生主线程都拖住；岛分页每页 50，截断不影响浏览体验；
/// - 标题统一 [Song.displayName]（与 App 其它展示点一致，剥离 .mp3 等后缀）；
/// - payload 带 [Song.id]：原生 media id 用稳定键寻址，不受队列插删漂移影响；
/// - attach 时主动 [LibraryProvider.loadSavedSongs]（读缓存幂等）——否则用户
///   不进"本地音乐"页时该树恒空。
class VivoMixSync {
  VivoMixSync._();

  static final VivoMixSync instance = VivoMixSync._();

  /// 本地列表推送上限（对齐岛端分页深度；避免全库过通道）。
  static const int _localCap = 500;

  PlayerProvider? _player;
  FavoritesProvider? _favorites;
  LibraryProvider? _library;
  Timer? _debounce;
  String? _lastPayloadFingerprint;

  /// App 首帧后调用（provider 均已创建）；重复调用为换绑（引擎重建场景）。
  void attach({
    required PlayerProvider player,
    required FavoritesProvider favorites,
    required LibraryProvider library,
  }) {
    _detachListeners();
    _player = player;
    _favorites = favorites;
    _library = library;
    player.addListener(_schedule);
    favorites.addListener(_schedule);
    library.addListener(_schedule);
    MediaNotificationService.onVivoMixPlay = _handleVivoMixPlay;
    // 本地库默认懒加载（只有进"本地音乐"页才读）——主动补一次缓存读取，
    // 让"下载列表"树在冷启动后也有内容。
    unawaited(library.loadSavedSongs());
    _schedule();
  }

  /// App 退出/引擎重建时解绑（app.dart 的 dispose 会调用）。
  void dispose() {
    _detachListeners();
    _debounce?.cancel();
    _debounce = null;
    _player = null;
    _favorites = null;
    _library = null;
    if (MediaNotificationService.onVivoMixPlay == _handleVivoMixPlay) {
      MediaNotificationService.onVivoMixPlay = null;
    }
  }

  void _detachListeners() {
    _player?.removeListener(_schedule);
    _favorites?.removeListener(_schedule);
    _library?.removeListener(_schedule);
  }

  void _schedule() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), _push);
  }

  void _push() {
    final player = _player;
    final favorites = _favorites;
    final library = _library;
    if (player == null || favorites == null || library == null) return;

    final currentSongs = player.playlist;
    final favoriteSongs = favorites.favorites;
    final localSongs = library.allSongs.take(_localCap).toList(growable: false);

    // 内容指纹：三棵树的 id 串未变就不推（高频 notify 下免重复编码/IPC）。
    final fingerprint = _fingerprint(currentSongs) + '|'
        + _fingerprint(favoriteSongs) + '|'
        + _fingerprint(localSongs);
    if (fingerprint == _lastPayloadFingerprint) return;
    _lastPayloadFingerprint = fingerprint;

    // ignore: discarded_futures
    MediaNotificationService.updateVivoMixLists(
      current: currentSongs.map(_toMap).toList(growable: false),
      favorites: favoriteSongs.map(_toMap).toList(growable: false),
      local: localSongs.map(_toMap).toList(growable: false),
    );
  }

  String _fingerprint(List<Song> songs) =>
      songs.length.toString() + ':' + songs.map((s) => s.id).join(',');

  Map<String, dynamic> _toMap(Song song) => {
        'id': song.id,
        'title': song.displayName,
        'artist': song.artist,
        'album': song.album,
        'artworkUri': song.artworkUri,
        'durationMs': song.duration.inMilliseconds,
      };

  /// 岛端点播：media id = `<来源>:<songId>`。按来源找 Song 后交
  /// PlayerProvider 播放（稳定寻址，不受队列位移影响；找不到则放弃——
  /// 快照未覆盖的条目宁可无响应也不模糊匹配错歌）。
  Future<void> _handleVivoMixPlay(String mediaId) async {
    final player = _player;
    final favorites = _favorites;
    final library = _library;
    if (player == null || favorites == null || library == null) return;

    final sep = mediaId.indexOf(':');
    if (sep <= 0 || sep + 1 >= mediaId.length) return;
    final source = mediaId.substring(0, sep);
    final songId = mediaId.substring(sep + 1);

    Song? song;
    switch (source) {
      case 'queue':
        song = player.playlist.where((s) => s.id == songId).firstOrNull;
      case 'fav':
        song = favorites.favorites.where((s) => s.id == songId).firstOrNull;
      case 'local':
        song = library.allSongs.where((s) => s.id == songId).firstOrNull;
    }
    if (song == null) return;
    await player.playSong(song);
  }
}
