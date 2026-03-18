# FxxkLocaion

一个基于 **LSPosed** 的辅助模块，用于绕过 FakeLocation Pro 验证、绕过黑名单校验，并通过 **GNSS Hook** 提升定位模拟的兼容性与精度，使部分严格检测 GPS 的应用也能接受模拟定位结果。

## 项目简介

FxxkLocaion 面向需要更高定位模拟兼容性的场景，核心目标是：

- 绕过 FakeLocation Pro 验证，获得长期可用的 Pro 能力
- 绕过黑名单校验
- 增强 GNSS / GPS 模拟效果
- 提升部分严格校验定位来源的应用对模拟定位的接受度

从界面上看，项目主要围绕 FakeLocation 的常用能力进行增强，包含：

- **位置模拟**
- **路线模拟**
- **WIFI 模拟**
- **独立模拟**
- **GPS 位置查看**
- **附近基站查看**
- **附近 WIFI 查看**
- **运行模式与设置项管理**

## 界面预览

> 以下图片来自 issue 中提供的截图链接。

![界面截图 1](https://github.com/user-attachments/assets/efb8f25f-5bae-4eec-94a4-6f47e656916d)
![界面截图 2](https://github.com/user-attachments/assets/5c7d0420-7e18-47b1-98de-1befdd48baab)
![界面截图 3](https://github.com/user-attachments/assets/bfb742df-7628-4c7e-96a0-fe8bfd9a06b6)
![界面截图 4](https://github.com/user-attachments/assets/6ab338de-446d-4456-8b8b-6ca77785d2db)

## 适用场景

- 需要在设备上对位置进行更稳定的模拟
- 需要提升某些应用对模拟定位的兼容性
- 需要配合 FakeLocation 的位置、路线、WIFI 等能力使用

## 使用前提

使用本项目通常需要具备以下条件：

- Android 设备
- 已安装并启用 **LSPosed**
- 设备侧已正确安装可配合使用的 FakeLocation 相关应用/版本

> 具体兼容性与适配效果会受到系统版本、ROM、LSPosed 环境以及目标应用检测策略影响。

## 基本使用说明

1. 安装并启用 LSPosed 环境。
2. 安装本模块，并在 LSPosed 中为目标应用启用作用域。
3. 重启目标应用或按需要重启设备。
4. 打开 FakeLocation 相关界面，按需使用位置模拟、路线模拟、WIFI 模拟或独立模拟等功能。
5. 如遇到定位不生效或被检测，可结合 GNSS 相关能力、运行模式和设置项进一步调整。

## 注意事项

- 本项目更适合测试、研究与演示用途。
- 不同应用对模拟定位的检测方式不同，兼容性并非绝对一致。
- 请勿将本项目用于违反目标应用服务条款或当地法律法规的用途。

## 说明

当前仓库公开内容以项目说明为主；如后续补充安装包、更新日志或更多使用文档，可继续在本 README 中扩展。
