# H2Wallpaper - 氢风格动态壁纸

H2Wallpaper 是一款高度可定制的 Android 动态壁纸应用，其设计灵感来源于经典的氢OS壁纸风格。它允许用户选择自己的图片，并将其设置为具有视差滚动效果和优雅过渡动画的动态壁纸。

[![Made with Kotlin](https://img.shields.io/badge/Made%20with-Kotlin-1f425f.svg)](https://kotlinlang.org/)
[![API](https://img.shields.io/badge/API-31%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=31)

## ✨ 主要功能

- **两种 P1 样式**:
  - **经典氢风格 (Style A)**: 屏幕顶部显示部分图片，下方为纯色或从图片提取的颜色背景。
  - **氢斜风格 (Style B)**: 创新的倾斜分层样式，上下遮罩会随着壁纸滚动产生旋转动画，并带有模糊和内阴影效果，营造出独特的立体感。

- **高度可定制化**:
  - **P1 图片编辑**: 支持通过手势对 P1 图片进行平移、缩放，并可调整其在屏幕上的高度。
  - **P1 独立背景**: 在“氢斜样式”下，P1 层拥有独立的背景图，同样支持平移和缩放调整。
  - **颜色提取**: 自动从用户选择的图片中提取主色调，生成和谐的颜色备选板。
  - **背景模糊**: P2 背景层支持高斯模糊，模糊半径、迭代次数和用于优化的降采样率均可调节。
  - **滚动与过渡**: 精细调整壁纸的滚动灵敏度、P1 前景淡出和 P2 背景淡入的过渡效果。

- **现代化的 UI**:
  - **实时预览**: 所有参数调整都能在应用内 `WallpaperPreviewView` 中实时看到效果。
  - **Compose UI**: 配置界面完全使用 Jetpack Compose 构建，提供流畅的现代化用户体验。


## 📸 应用截图

| 经典氢风格 (Style A) | 氢斜风格 (Style B) |
|:-------------------:|:-----------------:|
| <img src="https://github.com/user-attachments/assets/c2eb3369-0858-4e4f-989a-ca02331c15ac" alt="经典氢风格" width="250"/> | <img src="https://github.com/user-attachments/assets/e960d7f8-cc54-44cd-b26e-addc716b530f" alt="氢斜风格" width="250"/> |

## 🛠️ 技术栈

本项目采用了一系列现代 Android 开发技术和库：

- **语言**: [Kotlin](https://kotlinlang.org/)
- **UI**: [Jetpack Compose](https://developer.android.com/jetpack/compose) 用于构建配置界面，传统 `View` 系统用于壁纸预览 (`WallpaperPreviewView`)。
- **架构**: MVVM (Model-View-ViewModel)，使用 [Android Jetpack ViewModel](https://developer.android.com/topic/libraries/architecture/viewmodel) 和 [LiveData](https://developer.android.com/topic/libraries/architecture/livedata)。
- **异步处理**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) 用于处理图片加载和位图处理等耗时操作。
- **图形处理**: 原生 `Canvas` API 进行绘制，[RenderScript](https://developer.android.com/guide/topics/renderscript/compute) (虽然已废弃) 用于高性能的高斯模糊效果。
- **依赖库**:
    - `androidx.palette` 用于颜色提取。
    - `androidx.appcompat` 和 `androidx.constraintlayout`。
    - `androidx.preference` 用于数据持久化。

## 🚀 如何构建

1.  克隆本仓库:
    ```bash
    git clone https://github.com/muzyakihoro/h2wallpaper.git
    ```
2.  使用最新稳定版的 Android Studio 打开项目。
3.  等待 Gradle 同步和构建完成。
4.  直接运行 `app` 模块到你的设备或模拟器上。

