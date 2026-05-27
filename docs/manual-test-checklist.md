# ClaudeNotifier 手动测试清单

每次发版前过一遍。

## 启动 / 端口

- [ ] 首次启动：菜单栏出现 🔔 图标
- [ ] `~/.config/claude-notifier/runtime.json` 写入了 port/pid/host
- [ ] 用 `lsof -nP -iTCP:6789` 确认监听
- [ ] 占用 6789 后再启动：自动 fallback 到 6790（重启 lsof 验证）

## Hook 接收

- [ ] curl POST 合法 payload → 200 OK
- [ ] curl POST schema=99 → 400
- [ ] 同 session_id 第二次 POST → 浮窗那一行替换、ts 更新

## UI

- [ ] 队列空 → 浮窗自动收起、徽标无数字
- [ ] 第一次有事件 → 浮窗自动弹出、Glass 声音
- [ ] 黄色背景 = notification；绿色 = stop；红色 = 失败
- [ ] iTerm/IDEA/VS Code 行有对应徽标
- [ ] VS Code 行同意按钮置灰
- [ ] unknown 行同意+跳转都置灰

## iTerm 路径

- [ ] 跳转：精准切到对应 session
- [ ] 同意：该 session 出现 "1" 并执行
- [ ] session 已关闭：行变红，提示"找不到对应的终端会话"

## IDEA 路径（Plan A 阶段）

- [ ] 同意/跳转 → 行变红，提示"未检测到 IDEA plugin"

## VS Code 路径

- [ ] 跳转：`open vscode://file/...` 激活 VS Code
- [ ] 同意：按钮置灰，不能点

## 偏好设置

- [ ] 右键菜单 → 偏好设置弹窗
- [ ] 关声音 → 再触发不发声
- [ ] 关自动弹出 → 收起状态时新事件不弹出，但徽标更新
- [ ] 开机自启切换 → 系统设置 → 通用 → 登录项里出现 ClaudeNotifier

## 失败容错

- [ ] App 关掉，curl POST → 连接被拒（hook 脚本侧 fallback 由 Plan B 验证）
- [ ] 浮窗"全部清空" → items 清零、自动收起
