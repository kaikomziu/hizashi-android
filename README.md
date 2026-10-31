<h1 align="center">Hizashi（日差し）for Android</h1>

<p align="center">
  Windows 版 <a href="https://github.com/kaikomziu/hizashi">Hizashi</a> の Android 版。<br>
  コマンドパレット・集中モード・リーダーモード・要素ブロッカーなどを備えた、日本語のブラウザです。
</p>

<p align="center">
  <a href="https://github.com/kaikomziu/hizashi-android/releases"><img alt="release" src="https://img.shields.io/github/v/release/kaikomziu/hizashi-android?include_prereleases&label=release&color=4f8a54"></a>
  <a href="LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/license-MIT-blue"></a>
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-3ddc84">
</p>

> **試験版です。** まだ実機での動作確認が十分ではありません。うまく動かないところは [Issues](https://github.com/kaikomziu/hizashi-android/issues) で教えてください。

## ダウンロードとインストール

1. [**Releases**](https://github.com/kaikomziu/hizashi-android/releases/latest) から `Hizashi-x.y.z.apk` をダウンロード（[公式サイト](https://kaikomziu.github.io/hizashi/)からも入手できます）
2. ダウンロードした APK を開く。「提供元不明のアプリ」の許可を求められたら、いま使っているブラウザ（またはファイル管理アプリ）に対して許可する
3. インストールして起動

必要なもの: **Android 10 以降**。表示には端末の「Android System WebView」（Chromium）を使います（ふつう最初から入っていて、Google Play で更新されます）。

> **「Play プロテクト」が警告を出すことがあります。** Google Play に出していない個人開発のアプリのためです。心配なときは、同じリリースにある SHA-256 で APK を照合できますし、ソースコードはここにすべて公開されています。

## 入っている機能

| | |
|---|---|
| コマンドパレット | 🔍 ボタン。タブ・ブックマーク・履歴・全コマンドをあいまい検索。`>` `@` `*` `?` で絞り込み、`1+2*3` のような計算もできます |
| アドレスバー | `yt ねこ`（YouTube）、`gh kotlin`（GitHub）など17個の検索ショートカット。検索エンジンも変えられます |
| 広告・トラッカーのブロック | 約110ドメインへの通信を止めます（ドメイン単位）。サイトごとにオフにもできます（🛡 ボタン） |
| 通信まわり | HTTPS 自動アップグレード（開けなければ http に戻す）、URL の追跡パラメータ（utm_* や fbclid など）の自動除去 |
| 強制ダークモード | サイトごと、または全サイト |
| リーダーモード | 本文だけを抜き出して表示（文字サイズ・テーマ・読了時間つき） |
| 要素ブロッカー | 邪魔な要素をタップで消去。サイトごとに記憶されます |
| 集中モード | 25分・50分・好きな時間で、YouTube や X などをブロック。サイトは編集できます |
| そのほか | タブ一覧・休止・復元、ページ内検索、ブックマーク、履歴、ダウンロード、リンク長押しメニュー、PC版サイト表示、サイトごとの文字サイズ、共有 |

「リンクを開くアプリ」として選べるので、Android の設定で既定のブラウザにもできます。

## できないこと・正直なところ

- **拡張機能には対応していません。**
- **Windows 版のすべての機能があるわけではありません。** ワークスペース・縦タブ・分割表示・クイックメモ・スクリーンショットなどは、Android 版にはありません。
- 広告ブロックは**ドメイン単位**です。サイト自身が配信する広告（YouTube の動画広告など）は止められません。
- **シークレットタブ・アカウント同期・他ブラウザからのインポートはありません。**
- Google アカウントでのログインは、「安全でないブラウザ」と判定されて失敗する場合があります（未検証）。
- パスワードの管理機能は持っていません。
- 自動更新はありません。新しい版は Releases から入手して、上書きインストールしてください（同じ鍵で署名しているので、データは残ります）。
- 画面の言葉は日本語のみです。

## プライバシー

利用状況・閲覧履歴・設定などを、開発者や第三者に**送信しません**（テレメトリなし、アカウントなし）。ブックマーク・履歴・設定は、端末の中だけに保存されます。通信は、開いたページと、あなたが操作した検索の通信だけです。自動バックアップも無効にしています。

## ビルド

Android SDK（API 34）と JDK 17 が必要です。[Gradle](https://gradle.org/) 8.9 で、

```bash
gradle assembleDebug
```

`app/build/outputs/apk/debug/` に APK ができます。リリース版は、タグ（`v0.1.0` など）を push すると GitHub Actions が署名してビルドします。

## ライセンス

[MIT](LICENSE)
