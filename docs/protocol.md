# プロトコル版 1

同一 LAN の TCP だけを使う。整数はビッグエンディアン。フレームは `u8 type` + `u32 length` + payload。payload の上限は 4MiB。

既定ポートは TCP `39721`、探索 UDP `39720`。mDNS のサービス型は `_atcast._tcp`。

## フレーム

| type | 名前 | 方向 | payload |
| --- | --- | --- | --- |
| 1 | HELLO | タブレット → PC | `u16 version`, `u8[16] tabletId`, `u8 trustMode`, `u8[16] nonce`, `u16 videoWidth`, `u16 videoHeight`, `u16 deviceWidth`, `u16 deviceHeight`, `u8 nameLength`, `utf8 name` |
| 2 | AUTH | PC → タブレット | PIN: `u8 0`, `u8 length`, `utf8 pin`。信頼: `u8 1`, `u8[16] pcId`, `u8[32] hmac` |
| 3 | AUTH_RESULT | タブレット → PC | `u8 code`。0 成功、1 PIN 不一致、2 未信頼、3 信頼モードオフ、4 ロック |
| 4 | TRUST_GRANT | タブレット → PC | `u8[16] tabletId`, `u8[32] token` |
| 5 | TRUST_REQUEST | PC → タブレット | `u8[16] pcId`, `u8 nameLength`, `utf8 name` |
| 16 | VIDEO_CONFIG | タブレット → PC | `u16 width`, `u16 height`, Annex-B（SPS/PPS） |
| 17 | VIDEO | タブレット → PC | `u64 ptsUs`, `u8 keyframe`, Annex-B |
| 18 | AUDIO_CONFIG | タブレット → PC | `u32 sampleRate`, `u8 channels`, AudioSpecificConfig |
| 19 | AUDIO | タブレット → PC | `u64 ptsUs`, raw AAC |
| 32 | INPUT_TOUCH | PC → タブレット | `u8 action`, `u16 x`, `u16 y`。action は 0 down、1 move、2 up、3 cancel。座標は映像内の 0〜65535 |
| 33 | INPUT_KEY | PC → タブレット | `u8 action`, `u16 keyCode`, `u32 meta`。action は 0 down、1 up |
| 34 | INPUT_SCROLL | PC → タブレット | `u16 x`, `u16 y`, `i16 delta` |
| 48 | STREAM_CONFIG | PC → タブレット | `u8 preset`。0 低遅延、1 バランス、2 高画質 |
| 49 | REQUEST_KEYFRAME | PC → タブレット | 空 |
| 126 | PING | PC → タブレット | `u64 clientUnixMs` |
| 127 | PONG | タブレット → PC | `u64 clientUnixMs`, `u64 serverUnixMs` |

名前は UTF-8 で最大 64 バイト。文字の途中では切らない。

認証が通るまで映像と音声は送らない。信頼の失敗とモードオフは、同じ接続のまま PIN を受け付ける。PIN を 5 回間違えると、そのアドレスを 30 秒拒否する。

## 信頼 HMAC

トークンは 32 バイトで、許可したとき一度だけ `TRUST_GRANT` で PC へ渡す。タブレットは `SHA-256(token)` だけを保存する。

再接続の HMAC は `HMAC-SHA256(key = SHA-256(token), message = HELLO の nonce)`。トークンそのものは再送しない。

登録の瞬間だけトークンが LAN 上を平文で通る。

## 探索ビーコン

UDP payload は `ATC1`、`u16 tcpPort`、`u8[16] tabletId`、`u8 nameLength`、名前。

## 画質

長辺を上限に合わせ、幅と高さは 16 の倍数に切り捨てる。16 未満にはしない。

- 低遅延: 長辺 1280、30fps、4Mbps
- バランス: 長辺 1920、60fps、10Mbps
- 高画質: 長辺 2560、60fps、20Mbps

エンコーダは B フレームなし、可能なら CBR と低遅延優先。初期プリセットは高画質。

共通バイト列は `protocol/fixtures/packets.bin` と `protocol/fixtures/beacon.bin`。再生成は `protocol/generate-fixtures.ps1`。
