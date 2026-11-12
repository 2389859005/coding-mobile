# 手机主题配色（2.2.5）

主题只调整配色。所有主题共用组件尺寸、圆角和行距；界面与正文字体独立选择系统黑体、衬线宋体、紧凑黑体或等宽字体，代码保持等宽；浅色、深色、跟随系统独立选择。旧版青庭、海湾、陶土、墨白设置迁移为默认 Catppuccin，不再改变阅读布局。

## 公开参考来源

- [Catppuccin 官方调色板](https://github.com/catppuccin/palette/blob/main/palette.json)：使用 Latte / Mocha 的背景、正文、蓝色和紫色关系。
- [Tokyo Night 官方调色板](https://github.com/folke/tokyonight.nvim/blob/main/lua/tokyonight/colors/storm.lua)及 [Night 背景](https://github.com/folke/tokyonight.nvim/blob/main/lua/tokyonight/colors/night.lua)：蓝色、青色、紫色、绿色、橙色；浅色分支为手机阅读场景调整后的对应配色。
- [Everforest 官方调色板与语义说明](https://github.com/sainnhe/everforest/blob/master/palette.md)：暖纸色 / 灰绿背景，搭配绿、蓝、粉紫、陶橙等前景。

本次已联网检索并读取上述公开配色文件；没有复制上游主题实现代码。

## 组件角色

| 主题 | 主要操作 | 辅助操作 / 链接 | 代码关键字 | 字符串 | 数值 / 强调 |
| --- | --- | --- | --- | --- | --- |
| Catppuccin · 奶咖 | 紫色 | 蓝色 | 紫色 | 绿色 | 桃橙 |
| Tokyo Night · 东京 | 蓝色 | 青色 | 紫色 | 绿色 | 橙色 |
| Everforest · 森林 | 橄榄绿 | 蓝青色 | 粉紫色 | 橄榄绿 | 陶橙 |

每套配色还分别定义画布、卡片、次级表面、正文、次级文字、边框、运行、等待、错误、代码文字，以及 diff 新增 / 删除的背景和前景。浅色原始强调色不足以承载手机小字时，会保留色相方向并加深，主要文字组合按至少 4.5:1 校验。

实现见 `packaging/android/native/NativeUi.java`，模拟器回归见 `tests/android/AppearanceRegression.java`。

设置里的真实渲染预览展示标题、重点文字、行内代码、语法与差异色。标题和设置图标使用辅助色，重点文字／数值使用强调色，代码关键字、字符串、注释与数值分别着色；缩进的代码围栏也能识别。选择字体同时影响标题、控件、输入框与正文，切换配色不会重置字体。
