<!-- 本文件由 KMK 完全重写，不是上游 README。rebase 冲突时保留本文件。 -->

<p align="center">
  <img width="160" src="./.github/readme-images/app-icon.png" alt="Komikku">
</p>

<h1 align="center">Komikku · 个人自建版</h1>

<p align="center">在官方正式版基础上叠加自用补丁，自动构建、签名并发布 APK。</p>

> ⚠️ **这不是官方仓库。** 官方项目请见 [komikku-app/komikku](https://github.com/komikku-app/komikku)。

---

## 这个仓库做什么

跟随**上游最新正式版（release tag）**，把少量自用补丁叠加上去，然后用 GitHub Actions 自动构建、签名、发布 APK。

官方代码始终来自上游；这个仓库只维护自己的补丁，并保证它们能干净地重放到新版本上。

## 自用补丁（相对官方）

| 补丁 | 解决什么问题 |
|---|---|
| 归档读取优化 | 大 CBZ 打开慢：改走 ZIP 中央目录、按偏移随机读取条目 |
| 封面落盘 | 已下载漫画的封面存进下载目录，源失效或清缓存也不丢；浏览时还会自动补齐 |
| 自签名发布 | 用自有密钥签名，可持续覆盖升级，不依赖官方私钥 |
| 应用内更新指向本仓库 | 「检查更新」检查的是本仓库的 Release |
| 同步健壮性 | 缺少 Google 凭据时自动跳过云同步，不再拖垮书库刷新 |

补丁以独立提交维护在 `personal` 分支；每次发布还会附带补丁包 `Komikku-patches-<版本>.zip`（在 Release 资产里）。

## 下载

到 [Releases](https://github.com/jldxnb/komikku/releases) 下载：

| 文件 | 适用 |
|---|---|
| `Komikku-arm64-v8a-<版本>.apk` | 绝大多数现代手机 |
| `Komikku-armeabi-v7a-<版本>.apk` | 较老的 32 位设备 |
| `Komikku-universal-<版本>.apk` | 不确定选哪个就用它（体积更大） |

**签名说明**：这里的 APK 使用自签名证书，与官方版签名不同 —— 从官方版切换过来需要先卸载官方版（请自行备份数据）；装过本仓库的版本之后，后续版本可以直接覆盖升级。

## 更新机制

上游发布新的正式版后，GitHub Actions 会自动把自用补丁重放到新版本上，构建、签名并发布 `kmk-<官方版本>`。

应用内的「检查更新」指向本仓库的 Release；上游发新版时应用会提示更新。

## 许可与致谢

上游代码遵循其原有许可（Apache License 2.0），本仓库的补丁同样以 Apache-2.0 发布。

感谢 [komikku-app/komikku](https://github.com/komikku-app/komikku) 及其上游项目（Mihon / TachiyomiSY）的开发者。本仓库与官方项目没有隶属关系，官方功能与内容的版权归原作者所有。
