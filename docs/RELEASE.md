# 发布流程

从「代码改完」到「GitHub Releases 上出现可安装的 APK」的完整步骤。

## 0. 先把 keystore 找出来并备份（只做一次，但最重要）

你现有的包是用这把钥匙签的：

```
证书 DN      : C=CN, ST=guangxi, L=beihai, O=MyCompany, OU=Dev, CN=Jianhui Ding
SHA-1        : 6b63e6362a848e95ab164445b0d092bc6f6c8595
SHA-256      : 781ccf315cf1a57a61874fbb9d4d493d3e1a52de6c572e3f5813afecb58ac581
```

⚠️ **这把钥匙丢了，或者密码忘了，就再也没法给已安装的用户升级** —— Android 会校验新旧包签名是否一致，不一致只能卸载重装。务必把它（连同密码）离线备份一份。

> 下面第 2 步示例里的 `chatz.jks` / `alias chatz` 只是**新建** keystore 时建议用的名字。
> 你**现有的那把**沿用它原来的文件名和 alias，别照抄改掉 —— alias 换了对签名没影响，但 alias 填错会签不上。

如果还没有 keystore，生成一把（**一次生成，永久沿用**）：

```bash
keytool -genkeypair -v -keystore chatz.jks -keyalg RSA -keysize 2048 -validity 10000 -alias yezigotify
```

## 1. 升版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 22        // 每次发布必须 +1，只增不减
versionName = "1.7.3"   // 展示用的版本号
```

同时更新 [CHANGELOG.md](CHANGELOG.md)。

## 2. 出包

**方式 A：Android Studio 向导（推荐，不用改构建脚本）**

`Build` → `Generate Signed Bundle / APK` → 选 APK → 选 keystore → 填密码 → 选 `release` → 等构建完成。

**方式 B：命令行**

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（未签名）
```

然后对齐 + 签名：

```bash
zipalign -v -p 4 app-release.apk app-aligned.apk
apksigner sign --ks chatz.jks --ks-key-alias chatz --out Chatz-1.7.3.apk app-aligned.apk
```

（`zipalign` / `apksigner` 在 `$ANDROID_HOME/build-tools/<版本>/` 下）

## 3. 验签名

**这一步别省**，没签名或签错钥匙的包装不上真机：

```bash
apksigner verify --print-certs Chatz-1.7.3.apk
```

应该看到 `Verifies`，并且证书 DN 与上面那把一致。

## 4. 传到 GitHub Releases

命令行：

```bash
gh release create v1.7.3 \
  Chatz-1.7.3.apk \
  --title "v1.7.3" \
  --notes "从 CHANGELOG 里拷这次的更新说明"
```

或者网页上：Releases → Draft a new release → 新建 tag `v1.7.3` → 把 APK 拖进去 → Publish。

> 仓库必须是 **Public**，否则别人下载 Release 附件要先登录。

## 5.（可选）让 CI 替你做 2~4

仓库里已经放了一份 [`.github/workflows/release.yml`](.github/workflows/release.yml)：push 一个 `v*` 的 tag 就自动构建、签名、发 Release。

启用前需要在仓库 `Settings → Secrets and variables → Actions` 里配 4 个 secret：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 base64：`base64 -w0 chatz.jks` |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key 密码 |

配好之后发布就只剩两条命令：

```bash
git tag v1.7.3 && git push origin v1.7.3
```

## 命名建议

附件名带上版本号，方便用户分辨和回退：

```
Chatz-1.7.3.apk
```
