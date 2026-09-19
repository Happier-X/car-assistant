# 更新日志

本文件采用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式。

## [1.0.0] - 2026-09-19

### Added
- 开机 / 上电 / 手动三种触发方式，双触发去重
- WiFi 自动开启：六级策略链（API 直调 → 反射 → root svc → root cmd → 安全设置 → 无障碍 UI 兜底）
- 启动项管理：多应用按顺序启动，可调等待时间、排序、启停
- 应用启动：八级策略链，兼容 Android 10+ 后台启动限制
- 应用卸载：root `pm uninstall` / 系统卸载框 / 系统应用 `--user 0` 可恢复式卸载，含系统关键组件保护名单
- 环境自检：targetSdk 授权状态、root 可用性、无障碍服务、电池优化
- 运行日志：完整策略链 trace 记录，可一键复制导出
