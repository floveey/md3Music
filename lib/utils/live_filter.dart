import '../data/models/song.dart';

/// 「过滤 Live/现场版本」的标题识别（MD3 fork）。
///
/// 酷狗曲库没有可直接判别的来源标志位（Song 上无 type/remark），只能靠
/// 标题启发式：Live/现场 作为独立词出现（首尾都必须是起始/空白/括号/分隔符，
/// 不能是字母——防 "Alive"）。
/// ponytail: 极简启发式，两类罕见边缘不特判（真实误伤样本出现后再迭代）：
/// ① "Live and Let Die" 这类 Live 开头接英文介词的正经歌名会被误伤；
/// ② "Live at Wembley" 类 Live 后紧跟正文单词的形态识别不到。
final RegExp liveTitlePattern = RegExp(
  r'(^|[\s（(【\[\-—·\/|])(live|现场)(版|本)?\s*($|[\s\-—·\/|（）)】\]])',
  caseSensitive: false,
);

/// 歌曲标题是否为 Live/现场版本（如 "XXX (Live)"、"XXX - Live"、
/// "XXX (现场版)"、"XXX Live版"）。
bool isLiveSong(Song song) => liveTitlePattern.hasMatch(song.displayName);
