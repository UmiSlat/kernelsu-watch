# Watch 5 管理器迁移文档

日期：2026-10-28  
范围：从临时测试包迁移到 `io.github.umislat.kernelsu`  
仓库：`https://github.com/UmiSlat/kernelsu-watch`（private，`main`）

## 1. 目标

把手表上的 KernelSU 管理器从临时测试包迁移到正式 fork 包，同时保留当前
single-boot KSU 状态：

```text
旧：me.weishu.kernelsu        UID 10137，临时测试构建
新：io.github.umislat.kernelsu UID 10139，完整命名空间构建
```

迁移完成后应满足：

- Home 显示 Working / 版本 / LKM
- 管理器 root shell 可用
- 模块页、超级用户页可用
- 不依赖 nano proxy、`/system/bin/sh` bind
- 不需要重启手表

## 2. 当前状态（2026-10-28 00:30 CST）

### 2.1 仓库

```text
UmiSlat/kernelsu-watch
branch: main
HEAD:   896afff manager: rename namespace to io.github.umislat.kernelsu
```

- applicationId / Kotlin-Java namespace / AIDL / JNI / Manifest / Kbuild
  全部为 `io.github.umislat.kernelsu`
- App 名称：`KernelSU Watch`
- 仓库为 private

### 2.2 手表

```text
KSU:          Live，late-load，permissive
LKM:          kernelsu 5.15.202 + nosig
Kernel:       5.15.137-android14-11-maybe-dirty
Boot:         当前 boot 未重启；late-load 单 boot
屏幕:         keyguard 锁屏
```

旧包：

```text
package: me.weishu.kernelsu
UID:     10137
pid:     25922
fd:      66/71/80 -> anon_inode:[ksu_driver]
broker:  @ksu_driver_broker
root sh: 25948, 25956 (root)
flags:   runas 10137 ksud debug info -> 0x7
```

新包：

```text
package: io.github.umislat.kernelsu
UID:     10139
version: 30005 (KernelSU_Watch_33716c6_30005-debug.apk)
pid:     28504（以实际 pidof 为准）
fd:      无 [ksu_driver]
broker:  @ksu_driver_broker.io.github.umislat.kernelsu（已监听）
flags:   runas 10139 ksud debug info -> 0x5
root sh: 无；libksud 回退到 app UID 的 sh
```

新包已经能启动，没有 `UnsatisfiedLinkError`、`ClassNotFoundException` 或
AIDL 错误；当前唯一缺失是 **root -> app 的 driver fd**。

### 2.3 watcher

旧包 watcher 已恢复：

```text
/data/local/tmp/ksu_inject_watch.old
pid 28895
watch start last=25922
```

- 目标包：旧包 `me.weishu.kernelsu`
- 作用：旧 app 重启时重新注入 fd；当前旧 app 已有 fd，所以 watcher 暂时
  不需要动作
- 旧 watcher 二进制备份：`/data/local/tmp/ksu_inject_watch.old`

新包 watcher 曾构建并运行：

```text
/data/local/tmp/ksu_inject_watch_new
target: io.github.umislat.kernelsu
log:    /data/local/tmp/ksu_watch_new.log
```

日志显示：

```text
watch new pid=28504
attach 28504
mmap page=...
watch send_fd=1
shellcode stop sig=5
detached
```

但 app 的 `/proc/28504/fd` 里没有 `[ksu_driver]`；one-shot
`ksu_inject_watch_new running 28504` 也是同样结果。新包 watcher 目前停止，
旧包 watcher 恢复运行。

### 2.4 驱动

驱动只为旧 UID 完成过 crowning：

```text
KernelSU: Found manager base.apk ... is_manager: 1
KernelSU: Crowning manager: uid=10137
```

新 UID 10139 尚未 crowning；这与新 app 没有 fd 一致。

## 3. 为什么需要伴生进程

管理器 app 是 32 位用户态，app seccomp 直接拦截 32 位 `reboot` syscall 88。
late-load LKM 的 seccomp allow-cache 只覆盖 arm64 syscall 142，并且 late-load
setuid hook 也不会替 app 补 fd。

因此：

```text
app 自己：
  reboot(88) -> SIGSYS
  没有 fd -> libkernelsu 看不到驱动
  没有 root

root 侧：
  没有 app seccomp
  可以用 reboot magic 创建真实 [ksu_driver] fd
```

app 内已经有的 `DriverFdBroker` 只能把 **app 内已有的 fd** 转给 `libksud`
子进程；它不能自己创建 fd。所以“root -> app”必须由 root 侧完成。

不能把 watcher 原样塞进 app：

- app 不能调用被 seccomp 拦截的 syscall
- app 不能 ptrace 自己来创建 fd
- app 是 `untrusted_app`，不能以 root 运行

## 4. 迁移路线

### 4.1 推荐：root agent 作为 fd provider

```text
root bootstrap
  -> root agent（已有 ksu_agent 或新 fd agent）
       -> 创建 [ksu_driver] fd
       -> app 连接 agent，SCM_RIGHTS 收 fd
  -> app DriverFdBroker
       -> 把 fd 副本交给 libksud 子进程
  -> 官方 libksud debug su / Home / 模块 / 超级用户
```

要点：

- agent 必须是 root，由 per-boot bootstrap 启动
- agent 用 `SO_PEERCRED` 只接受 manager UID（10139），不能像旧
  `ksu_agent` 一样接受任意连接
- app 的 `DriverFdBroker` 在 `/proc/self/fd` 找不到 fd 时，向 agent 请求一次
- 成功后不再需要 watcher、ptrace、shellcode
- app 重启后重新向 agent 请求 fd；agent 不随 app 重启而消失

优点：没有 ptrace 注入竞态；实现比 LKM 改动小；保留官方 `ksud` 路径。  
缺点：仍然需要一个 per-boot root 伴生进程。

### 4.2 长期：LKM 暴露新入口

给 LKM 增加一个 app 可调用的入口：

- `/dev/kernelsu` 设备节点，或
- 一个不受 app seccomp 影响的 ioctl

app 直接 `open/ioctl` 拿 fd，不再需要 watcher 或 root agent。  
这是最干净的方案，但需要 **5.15.137 对应内核源码、`.config`、
`Module.symvers`** 重新编译 LKM；当前只有 5.15.202 nosig LKM，暂时做不了。

### 4.3 过渡：新包 watcher

保留 watcher 方案，但把 `APP_PKG`/`APP_ACT` 指向新包。当前已经这样做，
但 fd 没有落到 app；需要继续查：

- `send_fd=1` 后 app 的 `/proc/<pid>/fd` 为什么没有 fd
- app 的 `libsu`/`KsuCli` 是否在注入后关闭了 fd
- shellcode 的 `recvmsg` 是否实际收到 fd
- app 的 `DriverFdBroker` 是否在 fd 到达前就超时

这条路线只建议作为临时验证，不建议作为最终方案。

### 4.4 可选：Shizuku / shell UserService

Shizuku UserService 以 shell（uid 2000）运行，没有 app seccomp，可以创建
fd 并通过 Binder 传回 app。需要 Shizuku 运行、manager 增加 UserService
和 fd 回传逻辑，且驱动对 shell/manager 的识别要实测。不是纯应用内方案。

## 5. 迁移步骤

### Phase 0：备份与冻结

- 不重启、不 `unload`、不 `rmmod`
- 旧 manager 保持运行；旧 watcher 保持运行（当前 pid 28895）
- 备份材料：

```text
D:\smallprojects\watch5-rollback\
  kernelsu-old.apk                 旧 manager
  kernelsu-watch-debug.apk         新 manager
  ksu_manager_data_20261027.tar    旧 app 数据
  watcher-new\ksu_inject.c          新包 watcher 源码
  watcher-new\ksu_inject_watch_new  新包 watcher 二进制
```

设备上：

```text
/data/local/tmp/ksu_inject_watch.old   旧包 watcher 备份
/data/local/tmp/ksu_inject_watch_new   新包 watcher
/data/local/tmp/ksu_watch_old.log      旧包 watcher 日志
/data/local/tmp/ksu_watch_new.log      新包 watcher 日志
```

### Phase 1：新包安装（已完成）

- 完整命名空间 APK 已构建
- 已 side-by-side 安装，UID 10139
- 新包 broker socket 已监听
- 新包启动无 JNI/AIDL 异常

### Phase 2：选择 fd 供给方案

推荐 4.1 root agent：

1. 在 agent 中增加 fd 请求命令
2. app `DriverFdBroker` 增加 agent 回退
3. 启动 agent（root）
4. force-stop/start 新包
5. 验证 `/proc/<new_pid>/fd` 有 `[ksu_driver]`
6. 验证 `runas 10139 ksud debug info` -> `flags: 0x7`
7. 验证 `ps` 中新 app 的 `sh` 子进程为 root

如果继续 4.3 watcher：

1. 停旧 watcher
2. 启动新 watcher
3. force-stop/start 新包
4. 查看 `ksu_watch_new.log`
5. 查看 `/proc/<new_pid>/fd`
6. 如果仍无 fd，停止继续重试，转 4.1

### Phase 3：切换

新包验证通过后：

1. 确认新包 Home/模块/超级用户可用
2. 停止旧 watcher
3. 卸载旧包 `me.weishu.kernelsu`
4. 保留旧 APK/数据备份
5. 保留新 watcher 或 agent 启动方式
6. 更新 `watch/README.md` 与本节状态

### Phase 4：清理

- 删除旧包残留
- 只保留一个 `KernelSU Watch` 应用
- 记录新包的 UID、fd、broker socket、agent/watcher pid
- 更新 manifest/hashes（如需）

## 6. 验收清单

```text
[ ] 新包 pid 存在
[ ] /proc/<pid>/fd 有 anon_inode:[ksu_driver]
[ ] runas 10139 ksud debug info -> flags 0x7
[ ] 新 app 下有 root 的 sh 子进程
[ ] Home 显示 Working / 版本 / LKM
[ ] 无“未安装”卡片
[ ] 无“获取 Root 失败”banner
[ ] 模块页可打开
[ ] 超级用户页可打开
[ ] logcat -b crash 无新 SIGSYS
[ ] 不需要 nano proxy / sh bind
[ ] 旧包已卸载或明确保留为回滚
```

## 7. 回滚

旧包仍可用：

- 旧 watcher：`/data/local/tmp/ksu_inject_watch.old`（当前 pid 28895）
- 旧 manager：`me.weishu.kernelsu` UID 10137，当前有 fd
- 旧 APK：`D:\smallprojects\watch5-rollback\kernelsu-old.apk`
- 旧 app 数据：`ksu_manager_data_20261027.tar`

如果旧 manager 被卸载：

1. 安装 `kernelsu-old.apk`
2. 启动旧 watcher
3. 启动旧 app，让 watcher 注入 fd
4. 如 UID 变化，重新确认 flags/crowning
5. 必要时恢复 app 数据（root，注意 UID 所有权）

不要为了回滚重启；当前 boot 的 late-load KSU 只有一个。

## 8. 已知问题与风险

1. **新包没有 fd**：新 watcher `send_fd=1` 但 app `/proc/<pid>/fd` 无 fd；
   one-shot 同样。根因未定，可能涉及注入后 fd 生命周期、`libsu` 初始化和
   `receiver` 时机。
2. **新 UID 未 crowning**：`flags 0x5`；与没有 fd 一致。旧 UID 10137 是 0x7。
3. **两个同名应用**：旧包和新包 label 都是 `KernelSU Watch`；切换完成前会
   同时存在。
4. **LKM 不匹配**：5.15.202 nosig LKM 在 5.15.137 上是 lab 桥；不是 release。
5. **签名**：新 APK 是本地 debug key；不能作为正式发布。
6. **per-boot**：KSU、watcher/agent 都不跨重启；重启后需要重新建立。
7. **旧 watcher 曾停止**：已恢复 pid 28895；旧 app 当前 fd 仍在。不要让旧
   app 重启而 watcher 不在。
8. **视觉未确认**：keyguard 锁屏，Home/模块/超级用户截图仍待解锁。
9. **agent 安全**：旧 `ksu_agent` 接受任意本地连接并 fork root shell；
   改 fd provider 时必须加 `SO_PEERCRED` 只允许 manager UID。

## 9. 下一步建议

优先做 4.1：

```text
root agent fd provider
  + app DriverFdBroker 回退
  + 新包 force-stop/start
  + 验证 fd / flags 0x7 / root sh
```

如果 4.1 成功：

- 卸载旧包
- 停旧 watcher
- 更新 README / 本文档
- 保留旧 APK/数据作为回滚

如果 4.1 失败：

- 回到 4.3 调试 watcher 注入
- 或暂停迁移，继续保持旧包

长期再评估 4.2 LKM 新入口；在拿到 5.15.137 源码/config/Module.symvers 之前，
不要把它当成可交付方案。
