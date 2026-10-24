import 'dart:async';

import '../../data/models/song.dart';
import '../../providers/favorites_provider.dart';
import '../../providers/library_provider.dart';
import '../../providers/player_provider.dart';
import 'media_notification_service.dart';

/// vivo 原子随身听（vivomusicmix）列表快照推送器。
///
/// 岛（com.vivo.musicwidgetmix）在能力位放行后，会经 MediaBrowserServiceCompat
/// 绑定取三棵列表树：当前播放列表 / 收藏列表 / 下载列表。原生侧只读内存快照，
/// 因此这里由 Dart 主动推送：队列、收藏、本地歌任一变化即（去抖后）推一版。
///
/// 已知取舍：本地列表按"非在线"过滤（本地文件/云盘已落地），条目上限由原生侧
/// 分页消费，本类只负责快照；快照可能略滞后于界面（去抖 300ms），对列表展示无感。
class VivoMixSync {
  VivoMixSync._();

  static final VivoMixSync instance = VivoMixSync._();

  PlayerProvider? _player;
  FavoritesProvider? _favorites;
  LibraryProvider? _library;
  Timer? _debounce;
  bool _attached = false;

  /// App 首帧后调用（provider 均已创建）。
  void attach({
    required PlayerProvider player,
    required FavoritesProvider favorites,
    required LibraryProvider library,
  }) {
    if (_attached) return;
    _attached = true;
    _player = player;
    _favorites = favorites;
    _library = library;
    player.addListener(_schedule);
    favorites.addListener(_schedule);
    library.addListener(_schedule);
    _schedule();
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
    final current = player.playlist.map(_toMap).toList(growable: false);
    final favs = favorites.favorites.map(_toMap).toList(growable: false);
    final local = library.allSongs
        .where((s) => !s.isOnline)
        .map(_toMap)
        .toList(growable: false);
    // ignore: discarded_futures
    MediaNotificationService.updateVivoMixLists(
      current: current,
      favorites: favs,
      local: local,
    );
  }

  Map<String, dynamic> _toMap(Song song) => {
        'title': song.title,
        'artist': song.artist,
        'album': song.album,
        'artworkUri': song.artworkUri,
        'durationMs': song.duration.inMilliseconds,
      };
}
