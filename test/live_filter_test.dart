import 'package:flutter_test/flutter_test.dart';

import 'package:md3music/data/models/song.dart';
import 'package:md3music/utils/live_filter.dart';

Song _song(String title) => Song(
      id: '1',
      title: title,
      artist: 'a',
      album: 'b',
      duration: const Duration(seconds: 200),
    );

/// 「过滤 Live/现场版本」的正则判定自检：改名/挪形态必须连跑此测试。
void main() {
  test('live 版本命中', () {
    final live = [
      '一个像夏天一个像秋天 (Live)',
      '是妈妈是女儿(Live)',
      '是妈妈是女儿（现场）',
      '是妈妈是女儿（现场版）',
      '是妈妈是女儿 - Live',
      '是妈妈是女儿/Live',
      '是妈妈是女儿 Live版',
      '是妈妈是女儿.mp3 (Live)', // displayName 剥扩展名
    ];
    for (final t in live) {
      expect(isLiveSong(_song(t)), isTrue, reason: '应命中: $t');
    }
  });

  test('正常歌名不命中', () {
    final normal = [
      '一个像夏天一个像秋天',
      'Alive',
      'Livin\' on a Prayer',
      '孤独现场会', // "现场" 前后有字，不是独立词
      '一个像夏天一个像秋天.mp3',
    ];
    for (final t in normal) {
      expect(isLiveSong(_song(t)), isFalse, reason: '不应命中: $t');
    }
  });
}
