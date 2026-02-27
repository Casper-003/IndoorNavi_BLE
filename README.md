# 📍 IndoorNavi: 基于 BLE 与 WKNN 算法的室内定位导航系统

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4CAF50.svg?logo=android)
![BLE](https://img.shields.io/badge/Bluetooth-BLE%204.0%2B-0082FC.svg?logo=bluetooth)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

IndoorNavi 是一款基于 Android 平台的低功耗蓝牙 (BLE) 室内定位应用，专为缺少 GPS 信号的室内环境（如商场、车库、教学楼）设计。本项目不仅实现了完整的位置指纹定位工作流（离线建库 + 在线解算），还提供了一套专为学术评估打造的“开发者性能测试台”。

## ✨ 核心特性

* 📡 **高频信号采集**：实时扫描 BLE 信标 (Beacons)，结合平滑滤波算法剔除 RSSI 异常跳变。
* 🧠 **WKNN 算法引擎**：内置加权 K-近邻 (Weighted K-Nearest Neighbors) 算法，相比传统 KNN 显著提升定位精度。
* 🗺️ **纯净点阵雷达引擎**：弃用低效的列表组件，采用 Android底层 `Canvas` 绘制的无极缩放交互式雷达地图。
* 📱 **全形态自适应布局**：基于 `BoxWithConstraints` 实现响应式设计，完美适配手机竖屏与平板/PC横屏（左右分栏工作台）。
* 🌓 **深色模式支持**：全局 Material Design 3 规范，无缝跟随系统深色/浅色模式切换。
* 🛠️ **开发者评估模式**：
    * 动态调节 $K$ 值与算法类型 (KNN vs WKNN)。
    * 开启/关闭基于 EMA（指数移动平均）的坐标级轨迹防抖。
    * 点击雷达图设定基准真值 (Ground Truth)，**实时动态测算定位误差 (米)**。

## 📐 算法原理 (Algorithm)

本系统采用位置指纹法 (Location Fingerprinting)。在在线定位阶段，系统收集实时接收信号强度指示 (RSSI)，并计算其与离线指纹库中各参考点特征向量的欧式距离：

$$D_i = \sqrt{\sum_{j=1}^{n} (S_j - F_{ij})^2}$$

获取最近的 $K$ 个参考点后，系统采用距离的倒数作为权重进行加权平均解算，从而得出最终的物理坐标，有效缓解了信号衰减非线性带来的定位误差。

## 📸 系统截图

*(💡 提示：将你的截图重命名为 1.png, 2.png, 3.png 放在项目的 `images` 文件夹下，即可在此处显示)*

| 基站锁定与管理 | 沉浸式指纹采集 | 平板模式 & 开发者评估台 |
| :---: | :---: | :---: |
| <img src="images/1.png" width="250"/> | <img src="images/2.png" width="250"/> | <img src="images/3.png" width="250"/> |

## 🚀 快速开始

### 1. 环境要求
* Android Studio Iguana | 2023.2.1 或更高版本
* Android 8.0 (API 26) 及以上物理真机（**必须具备蓝牙硬件，不支持模拟器测试**）
* 至少 3 个 BLE 蓝牙信标 (Beacons)

### 2. 使用工作流
1.  **基站配置**：进入【基站管理】，扫描并锁定场景内部署的至少 3 个目标 Beacon。
2.  **离线建库**：进入【指纹管理】，设置物理空间的宽、长与采样间距，生成点阵地图。手持设备在对应网格点点击“采集”，完成后可导出为 CSV 文件。
3.  **在线定位**：进入【定位测试】，引擎将自动加载指纹数据并在雷达图上生成跳动的红点（当前预测坐标）。
4.  **性能测算**：在【设置】中开启“开发者性能评估模式”，返回定位页即可通过点击地图生成蓝色真值点，并实时拉线测算误差。

## 📁 核心目录结构
```text
com.example.indoornavi/
│
├── MainActivity.kt        # 宿主 Activity 与响应式主路由
├── BleScanner.kt          # BLE 蓝牙高频扫描与 RSSI 平滑处理模块
├── WknnLocator.kt         # 核心数学大脑：KNN/WKNN 定位解算引擎
├── SharedViewModel.kt     # 全局状态管家，解决后台数据保活问题
└── ...
```
👨‍💻 开发者与致谢
Core Developer: [你的名字]

Contact: [你的邮箱]

Institution: [你的大学/学院名称]

本项目为本科毕业设计成果。UI 层全面采用 Kotlin + Jetpack Compose 现代声明式 UI 框架构建。

📄 开源协议
This project is licensed under the MIT License.
