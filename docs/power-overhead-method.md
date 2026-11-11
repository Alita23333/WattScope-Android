# CPU 功耗开销测量

## 观测范围

`tools/cpu_power_probe.cpp` 是独立于 App 的常驻原生读取器，读取 QPT `energy_uj` 与 `power_uw`。它不读取 CPU 占用率，也不把运行时间百分比换算成 W。

当前 K90 Pro Max（myron）发现的 CPU 电源域是 `cpu-m`、`cpu-l`，都属于 SoC 下面的同级域。分别保留原始名称；CPU 合计只合并这两路，不添加 `soc`、GPU、NSP 或 `debug-*`。此接口不能提供每颗物理核心和每个进程独立的功耗。

## 计算

对相邻有效观测：

```
E_CPU_J = Σ(energy_uj_end - energy_uj_start) / 1,000,000
P_CPU_mW = Σ E_CPU_J / Σ valid_duration_s × 1,000
增量 mW = 开启时 CPU 平均功耗 - 基线 CPU 平均功耗
```

以下区间不参与积分：QPT 关闭、计数器缺失／重置、时间倒退，或采样间隔超过设置值的 2.5 倍。合法零值保留。CPU 合计使用各 CPU 域同时有效的时间，避免不同覆盖区间相加。每个 rail 还单独给出自身覆盖率。

## 测试顺序和条件

每种模式采用 A1（基线）→ B1（开启）→ B2（开启重复）→ A2（基线复测）。支持仅记录、记录小窗、仪表 OSD、文字 OSD、双窗和单独的 100 ms 采样测试。

- 所有阶段使用相同独立观测器、相同读取间隔、相同游戏画面、亮度、刷新率与供电状态。
- 测试程序先等待预热，再保存原始 JSONL；准备和切换阶段不计入测量。
- 开始和结束都核对真实记录状态、前台包名、记录 ID 和有效时长增长。状态错误的阶段保留原始值，但不能证明对应功能的开销。
- 保存 CPU 和电池温度范围、基线前后差、开启阶段之间的差和有效覆盖率。单个短测块只用于验证流程。
- 设备上的其他悬浮监控和后台任务须记录在报告中。没有控制住的波动不能归因给 WattScope。
- QPT 和独立观测器在基线中也保持开启，测量的是在同一观测条件下启用 App 功能的增量。读数仍包含该电源域上所有系统任务和游戏负载。

## 复现

先用现有 NDK 编译观测器，再运行对照程序；程序仅操作指定序列号的手机。

```powershell
& 'C:\DevTools\Activate-DevTools.ps1'
.\tools\build-power-probe.ps1
python -m unittest discover -s tools -p test_power_stats.py -v
python tools\power_benchmark.py --serial YOUR_DEVICE_SERIAL --game com.miHoYo.Nap --game-name 绝区零 --modes recording small osd-gauge osd-text both --seconds 120 --warmup 30 --scenario "实际观察到的固定画面" --output evidence\power-run-new
```

测试使用本工程 Benchmark APK。它与 Release 一样启用 R8 和资源压缩，关闭可调试标志，仅增加不导出的测试入口；Debug 也开放这些入口，但 Debug 的功耗不能替代优化版测量。常规用户操作不依赖这些入口。每轮原始数据保存 SHA-256；`report.json` 包含阶段状态和计算结果。中断测试后应确认本 App 服务已退出，并按报告中的原始状态恢复 QPT。

CPU 调度诊断脚本 `perf_measure.py` 和此前的 CPU 百分比结果不属于功耗验收证据。功耗通过阈值待用户另行确定，程序不会自行判定通过。
