**2026/07/19**

* AI 书源生成增强：fetch_html 支持 POST/编码检测/自定义 headers/内容提取模式
* AI 工具集扩展：新增 check_book_sources(批量书源检测)、read_stats(阅读统计)、validate_book_source(书源验证)
* AI Agent 改进：指数退避重试、工具超时保护、连续错误计数优雅退出、maxSteps 提升到 8
* 重构 ReadBookActivity：提取 KeyEventHandler 和 ReadAloudDelegate（减少 Activity 约 630 行）
* 新增阅读目标系统：每日阅读时长目标、连续打卡、阅读提醒间隔
* 更新 CLAUDE.md 文档反映新架构

**2022/10/02**

* 更新cronet: 106.0.5249.79
* 正文选择菜单朗读按钮长按可切换朗读选择内容和从选择开始处一直朗读
* 源编辑输入框设置最大行数12,在行数特别多的时候更容易滚动到其它输入
* 修复某些情况下无法搜索到标题的bug，净化规则较多的可能会降低搜索速度 by Xwite
* 修复文件类书源换源后阅读bug by Xwite
* Cronet 支持DnsHttpsSvcb by g2s20150909
* 修复web进度同步问题 by 821938089
* 启用混淆以减小app大小 有bug请带日志反馈
* 其它一些优化
