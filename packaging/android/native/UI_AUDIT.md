# Android 原生 UI 静态审计报告

审计范围：`packaging/android/native/` 下全部 29 个 Java 文件，重点为 `MainActivity.java`（1018 行）、`StatusOverlay.java`（265 行）、`NativeUi.java`（221 行）、`GitPage.java`（64 行）、`DirectoryBrowser.java`（54 行）、`MessageContent.java`（107 行）。

说明：`read` 报告的行号为文件真实行号（含空行）。`Measure-Object -Line` 会把行号数少算若干，本报告一律以 `rg -n` 与 `read` 的输出为准。

---

## 严重

### S1. `StatusOverlay` 整套主题色硬编码，与 `NativeUi.theme()` 双份维护

`StatusOverlay.java:32`、`144`、`145–149`、`247`

```java
32:  private int background=0xffeaf1e9,foreground=0xff27634d;
144: background=0xffeaf1e9;foreground=0xff27634d;
145: if(theme.equals("dark")){background=0xff28322c;foreground=0xff92cbb0;}
146: if(theme.equals("ocean")){background=0xffdeedf7;foreground=0xff246d99;}
147: if(theme.equals("lavender")){background=0xffe8dcf2;foreground=0xff765198;}
148: if(theme.equals("sand")){background=0xffeedfc8;foreground=0xff896338;}
149: if(theme.equals("midnight")){background=0xff253d59;foreground=0xffd2e4fc;}
```

`background` 的值与 `NativeUi.TINT` 的浅色定义逐字相同（`NativeUi.java:16` `TINT=0xffeaf1e9`），`foreground` 与 `NativeUi.GREEN` 浅色定义相同（`NativeUi.java:13` `GREEN=0xff27634d`）。5 个主题分支里除 `dark` 外，其余 4 套（ocean/lavender/sand/midnight）都是 `NativeUi.java:35–38` 中 `TINT`/`GREEN` 的复制粘贴。

为什么是问题：悬浮窗是唯一一处**不经过** `NativeUi.theme()` 的状态色来源。`theme()` 一共有 7 个主题名（`NativeUi.java:25–38` 处理 light/dark/green/ocean/lavender/sand/midnight），而 `colors()` 只判断 5 个字符串。`green` 主题（`MainActivity.java:150` 把 `warm` 归一化后仍会落回 `green`）没有任何分支，靠 `144` 行的默认值兜底——恰好凑对。但只要将来 `NativeUi.theme()` 调整 `TINT`/`GREEN`，悬浮窗不会跟随，会出现悬浮窗和主界面不同色。另外 `MainActivity.java:150` 把 `warm` 改写成 `green` 并回写 SharedPreferences，而 `StatusOverlay.colors()` 不做这个归一化，若悬浮窗先于 Activity 启动，`warm` 会落到默认的 light 分支。

改法：删除 `background`/`foreground` 两个字段和整个 `colors()` 主题分支，改为一处调用：

```java
private void colors(){
    String theme=prefs().getString("theme","light");
    if(theme.equals("warm"))theme="green";
    NativeUi.theme(theme);
    foreground=NativeUi.GREEN;
    background=NativeUi.TINT;
}
```

`NativeUi.theme()` 是幂等的静态方法，只写静态字段，悬浮窗内调用安全。

### S2. `MainActivity.java:747` 直接写死紫色，同一行文本同时承载两种语义

```java
247:  ...backend.equals("claude")?0xff27634d:backend.equals("deepseek")?0xff8054a2:0xff305d77,12);
```

`0xff27634d` = `GREEN`，`0xff8054a2` = `KEYWORD`，`0xff305d77` = `CODE`（对照 `NativeUi.java:13,20`）。三色本身有名字，却按字面量写死。深色主题下 `CODE` 是 `0xffb9d9ef`、`KEYWORD` 是 `0xffd6b2f1`（`NativeUi.java:34`），与这里的浅色值完全不同——深色主题下这个图标颜色会明显偏暗、对比不足。

改法：`backend.equals("claude")?NativeUi.GREEN:backend.equals("deepseek")?NativeUi.KEYWORD:NativeUi.CODE`

### S3. `FlowBorderDrawable.java:11` 与 `ContextRing.java:19` 的散落颜色

```java
// FlowBorderDrawable.java:11
private static final int[] COLORS={0xff16d6ae,0xff43a4ff,0xff8965ff,0xffef67d1,0xffffc75c,0xff16d6ae};
// ContextRing.java:19
paint.setColor(fraction>=.9?NativeUi.RED:fraction>=.75?0xffffc75c:0xffffffff);
```

`FlowBorderDrawable` 的 6 色渐变完全不进主题，在任何主题下都是同一套高饱和青/蓝/紫/品红/橙。`ContextRing` 的 `0xffffc75c`（琥珀）在两处各写一遍且都是字面量。

为什么是问题：这两个是**唯一**绕过主题的可见描边/填充。在 sand、midnight 这类低饱和主题上，`FlowBorderDrawable` 的渐变会显得很突兀。`ContextRing` 的 `0xffffc75c` 在深色主题（`CANVAS=0xff111827`）上与 `0xffffffff` 的白色环对比度接近，75%–90% 区间视觉上分不出档位。

改法：`NativeUi` 增加 `static int WARN=0xffffc75c;`（dark 下改为 `0xffe0a94a`）并让 `ContextRing` 引用；`FlowBorderDrawable` 增加一个进主题的构造重载，至少把 `COLORS` 改为从 `NativeUi` 派生。

### S4. `MainActivity.java:487` 用 `top.getChildCount()-1` 插入，索引计算错误

```java
487: recordCount=text("0 轮对话",11,MUTED);recordCount.setPadding(dp(4),0,dp(8),0);
     top.addView(recordCount,top.getChildCount()-1);projectButton=null;
```

`top` 是 `LinearLayout row()`，在 `484–486` 行依次加入：`back`(48dp)、`identity`(weight=1)、`info`(48dp)。到 `487` 行时 `getChildCount()` 已经是 **3**，因此 `3-1=2`，`recordCount` 被**插到 `info` 之前**，实际顺序变成 `back, identity, recordCount, info`。

为什么是问题：意图显然是让轮次计数贴在右侧 `info` 按钮之前（或之后），但 `getChildCount()-1` 这种写法既不是"倒数第一位"也不是"追加"，读者的两种直觉都错。它现在能跑出看起来还行 的结果纯属巧合（因为 `identity` 是 weight=1 会吃掉余量）。一旦有人在 `486` 行后追加一个按钮，`recordCount` 的位置就会跳到新按钮之后。

改法：明确的意图应写成 append 再调整，或者直接 `top.addView(recordCount)` 追加到末尾（`info` 之后）。如果确实要在 `info` 之前：

```java
top.addView(recordCount,top.getChildCount()-1); // -1 才是 info 的位置
```

并把 `487` 行的 `2` 硬编码改成 `top.indexOfChild(info)`，让意图自解释。

### S5. 点击目标小于 48dp（无障碍规范）

统计：`MainActivity.java` 共 62 处 `setOnClickListener|setOnLongClickListener`，`dp(44)` 出现 14 次、`dp(48)` 出现 14 次，两者混用。以下点击宿主**自身**或父链的点击区域 < 48dp：

- `MainActivity.java:467` — `close` 按钮 `dp(44)×dp(44)`，是弹层关闭键，最需要留足。
- `MainActivity.java:507` — `attach` 按钮 `dp(44)×dp(44)`，附件入口。
- `MainActivity.java:436` — 项目分组行的 `create` 按钮 `dp(44)×dp(44)`（`group.addView(create,new LinearLayout.LayoutParams(dp(44),dp(44)))`）。
- `MainActivity.java:783` — 应用控制按钮 `dp(40)×dp(44)`，宽度和高度都不到 48。
- `MainActivity.java:505–506` — 三个 `option(...)` 下拉项高度 `dp(44)`，且有 `option.setTextSize(11)`，点击区就是 44dp 高。
- `MainActivity.java:488` — `earlierButton` 显式 `setMinHeight(0)` 后放在 `dp(32)` 高的容器里（`historyBar.addView(earlierButton,new LinearLayout.LayoutParams(-2,dp(32)))`），实际点击高度 32dp。这是**屏幕上最容易点错的按钮**：它悬浮在消息列表顶部（`492` 行 `topMargin=dp(58)`），误触会加载历史。
- `MainActivity.java:649` — 排队消息标题 `title.setMinHeight(0)` + `add(queuePanel,title,36)`，36dp。
- `MainActivity.java:653` — 队列操作按钮高度虽是 `dp(48)`，但 `action.setTextSize(11)` 且 `action.setPadding(dp(8),0,dp(8),0)`，三个按钮等分一行，窄屏（360dp 宽）下单个宽度约 `(360-32-2×0)/3 ≈ 109dp`，宽度够但文字可能换行。
- `MainActivity.java:980` — 附件移除按钮：`item` 整体 `dp(64)` 宽、`remove` 是 `dp(56)×dp(48)`，但**点击监听挂在 `item` 上**，`item` 高度是 `-2`（wrap_content），包含 `dp(42)` 的缩略图 + `dp(48)` 的移除文字 = 90dp 高，够。此处反而是**点击区域过大**，整个缩略图区域都会删附件，误触风险高。

为什么是问题：48dp 是 Material 最小触摸目标，也是 Android 无障碍扫描会报的项。`earlierButton`（32dp）和 `attach`/`close`（44dp）是三个最典型的违规。

改法：统一提升到 48dp。`earlierButton` 的高度改 `dp(48)`，去掉 `setMinHeight(0)`/`setMinimumHeight(0)`；`iconButton` 的调用点统一 `dp(48)`；`dp(44)` → `dp(48)`（`NativeUi.button()` 本来就已经 `setMinHeight(dp(48))`，`MainActivity` 却在 44/48 之间摇摆，说明约束没被当回事）。

### S6. `MainActivity.java:980` 附件删除挂在整行而非删除按钮上

```java
item.addView(thumbnail,new LinearLayout.LayoutParams(dp(50),dp(42)));
...
item.addView(remove,new LinearLayout.LayoutParams(dp(56),dp(48)));
item.setContentDescription("移除附件 "+image.optString("name"));
item.setOnClickListener(v->{...cache.images.remove(image);...});
```

`ImageView` 缩略图本身没有点击处理，点它等于点"移除"。用户点缩略图想预览，结果附件被删。且 `remove` 这个 `TextView` 自己反而没有 `setOnClickListener`。

改法：把监听从 `item` 移到 `remove`，缩略图改挂 `loadInlineImage`/预览：

```java
remove.setOnClickListener(v->{...});
thumbnail.setOnClickListener(v->previewAttachment(image));
item.setContentDescription(null);
```

`remove` 的高度 48dp 满足要求，宽度 56dp 也够。

---

## 中等

### M1. 字号层级混乱

统计：全目录 `setTextSize` 共 **22 处**，出现 **9 种**数值：`9 / 11 / 12 / 14 / 15 / 16 / 17 / 34`（另 1 处是 `NativeUi.text()` 的参数透传 `size`）。分布：

| 字号 | 次数 | 代表位置 |
|---|---|---|
| 9 | 1 | `MainActivity.java:503` `sendButton` |
| 11 | 2 | `MainActivity.java:506` 选项行、`653` 队列操作 |
| 12 | 7 | `384,385,488,499,649,866,900` |
| 14 | 5 | `NativeUi.java:110` button、`GitPage.java:38`、`MainActivity.java:584`、`934` |
| 15 | 1 | `NativeUi.java:127` input |
| 16 | 1 | `MainActivity.java:503` composer |
| 17 | 2 | `MainActivity.java:747` 桌面助手、`844` settingSwitch |
| 34 | 1 | `MainActivity.java:266` welcome |

问题点：

1. **次要文字比主要文字大**：`MainActivity.java:649` 排队标题 12，而 `MainActivity.java:747`「桌面助手」是 17。两者同属"列表可点标题"语义层级，差 5 号。`MainActivity.java:844` 的 `settingSwitch` 标签 17 与 `698` 的 `settingsGroup` 标签 17 一致，但 `703` 的 `settingsSection` 是 13 —— 设置页里 17/13/12 三层，还算成立。
2. **同一语义层级用了 4 种字号**：所有"可点列表行"分布在 `12`（`384,385,488,649,866,900`）、`14`（`584`、`GitPage.java:38`）、`17`（`747`、`844`）、`11`（`506`、`653`）。列表行字号应该收敛为 1–2 档。
3. **`MainActivity.java:503` 的 `sendButton.setTextSize(9)`**：按钮文字被设成 9sp 仅仅是为了不影响图标居中（图标通过 `ui.centerIcon` 的 `Drawable` 前景绘制，文字其实是空串 `button("",true)`）。9sp 在无障碍字体缩放下会放大，可能挤压 `dp(56)` 的按钮。建议直接不设字号，或 `setTextSize(0)` 的替代方案是保留默认 14 但 `setText("")`。

另外 `MainActivity.java:123` 的 `title()` 用 28，`266` 又覆盖成 34；`705` 设置页标题 24；`467` 弹层标题 20；`376` 提供方标题 24；`436` 分组头 16。**"页面标题"这一层级有 20/24/28/34 四种**。

改法：在 `NativeUi` 里定义常量并全部走常量：

```java
static final int SIZE_DISPLAY=34, SIZE_TITLE=24, SIZE_SHEET=20,
                 SIZE_BODY=16, SIZE_LIST=14, SIZE_LABEL=12, SIZE_CAPTION=11;
```

然后 `384,385,488,649,866,900` 全部用 `SIZE_LABEL`（12），`584`/`GitPage.java:38` 用 `SIZE_LIST`。

### M2. 间距不一致

`setPadding` 全目录 **85 处**（`MainActivity.java:50`、`GitPage.java:10`、`NativeUi.java:6`、`StatusOverlay.java:6`、`DirectoryBrowser.java:2`、`NativePreview.java:2`、`NativeDialogBuilder.java:3`、`MessageContent.java:2`、`FloatingNavigation.java:1`、`NetworkDialog.java:1`）。同一视觉层级的冲突实例：

**列表行水平内边距有 4 种：**

- `MainActivity.java:437` 会话行 `item.setPadding(dp(12),dp(10),dp(12),0)`
- `MainActivity.java:474` 项目选择行 `label.setPadding(dp(10),dp(18),dp(10),dp(18))`
- `GitPage.java:45` 文件行 badge 用 `badgeLayout.setMargins(ui.dp(4),0,ui.dp(10),0)`
- `DirectoryBrowser.java:30` 目录行 `label.setPadding(ui.dp(10),0,ui.dp(10),0)`
- `MainActivity.java:652` 队列文本 `text.setPadding(dp(8),dp(7),dp(8),0)`

**卡片内边距不统一：**

- `MainActivity.java:328` `inset(card,12,10)` — 待确认卡片
- `MainActivity.java:917` `inset(card,12,8)` — 权限卡片
- `MainActivity.java:929` `inset(card,14,12)` — 提问卡片
- `MainActivity.java:742` `inset(linkCard,14,12)` — 连接卡片
- `MainActivity.java:468` `panel.setPadding(dp(20),dp(10),dp(20),dp(16))` — 弹层

同是"卡片"，垂直内边距有 8/10/12 三种，水平有 12/14/20 三种。

**`inset()` 的所有调用值汇总**（`MainActivity.java`）：`14,12` ×2、`10,10`、`12,0`、`12,10`、`12,2`、`12,4`、`12,6`、`12,8`、`14,0`、`16,16`、`20,4`、`24,8`、`32,24`。水平方向 `10/12/14/16/20/24/32` 七种，垂直 `0/2/4/6/8/10/12/16/24` 九种。

**`add(parent,child,height)` 的高度值**：`0,1,26,28,30×2,32,36,40×5,44×8,48×19,50,52×7,54,56×6,60×2,68,74,118,168,260,340,360×2`。`52` 和 `48`、`56` 和 `60` 语义上完全重叠。

改法：定义间距刻度并收敛。例如只保留 `4/8/12/16/24`：

```java
static final int GAP_XS=4, GAP_S=8, GAP_M=12, GAP_L=16, GAP_XL=24;
```

列表行统一 `dp(12)` 水平；卡片统一 `14,12`；行高统一到 `48` 和 `56` 两档（`52` 与 `54`、`58`、`60` 全部并入）。

### M3. 分隔线未走 `NativeUi.rule()`

`MessageContent.java:95`：

```java
View rule=new View(context);rule.setBackgroundColor(NativeUi.LINE);
```

`MainActivity.java:466`：

```java
grip.setBackground(ui.surface(NativeUi.LINE,2,false));
```

对照 `NativeUi.java:67–73` 的注释，作者已经明确判定 `LINE` 太淡：

> `LINE alone is nearly invisible against the canvas on every theme`
> `MainActivity.java:130`: `NativeUi.LINE sat at 1.15-1.24:1 against the canvas, which is below the point where a 1px rule reads as a divider at all.`

因此 `MainActivity.java:132` 引入了 `rule()`，用 `NativeUi.rule(CANVAS)` 做混合。但 `MessageContent.java:95` 的代码块分隔线和 `MainActivity.java:466` 的弹层 grip 仍然直接用 `LINE`。

`MessageContent.java:95` 的分隔线在 `WHITE` 卡片上（`87` 行 `ui.surface(NativeUi.WHITE,14,true)`），`LINE` 对 `WHITE` 的对比度同样接近 1.2:1。按作者自己的判据，这两条线在多数主题上"读不出来"。

改法：`MessageContent.java:95` 改为 `rule.setBackgroundColor(NativeUi.rule(NativeUi.WHITE));`；`MainActivity.java:466` 改为 `ui.surface(NativeUi.rule(NativeUi.WHITE),2,false)`。

### M4. 文字截断覆盖不足

统计：`MainActivity.java` 有 `setMaxLines` **8 处**，`setEllipsize|setSingleLine|single(` **18 处**，但 `text(...)` 调用族有 **65 处**。也就是说 **约 65 个 TextView 里只有 18 个处理了溢出**，覆盖率约 28%。可能很长的内容却未设截断：

- `MainActivity.java:485` `chatDirLabel=text("",13,MUTED);single(chatDirLabel);` —— 有 `single()`，好。但同一行的 `connectionLabel=text("",12,MUTED);`（`485` 行末）**没有任何截断**，它会被 `connection()` 写入 `"助手可达 · 应用未连接"`（`403`）甚至 `appStatusUntil` 期间写入完整服务器消息（`842`: `badge.setText(result.optString("message","操作已提交"))`）。服务器返回的消息长度不可控。
- `MainActivity.java:386` `status=text("",12,MUTED);` 后续被写入 `cache.error`（`404`：`status.setText(cache.error+(...))`）与 `errorMessage(e)` 的完整文本（`322`）。错误消息含 HTTP 原因，长度不可控，且 `status` 在 `heading` 里是 `add(heading,status,-2)`，会**把整个 heading 撑高**。
- `MainActivity.java:500` `status=text("",12,NativeUi.RED);` 同样接收 `errorMessage(e)`（`625`）与 `result.optString("message",...)`（`677,690`），无截断。
- `MainActivity.java:539` `metadata()` 里的 `detail=text(value,15,INK)` —— 值可能是完整文件路径（`548` 传 `chatPath`）。**有** `setTextIsSelectable(true)` 但**没有** `setMaxLines`，长路径会占很多行。
- `MainActivity.java:502` 之后 `status` 通过 `changed()` 的 `TextWatcher` 控制可见性（`500`），这个 `TextWatcher` 在 `status` 每变一次都跑 `setVisibility`，加上 `getText().length()` 分配，是热路径上的廉价实现——顺便一提，`getText().length()` 比 `getText().length()` 更该写成 `length()==0`。

为什么是问题：`metadata()` 是最典型的——它在 `FormScroll`（`551`：`new NativeUi.FormScroll(this)`，上限 380dp 或半屏）里，长路径会让对话信息表滚动很多屏。

改法：

```java
// 485 行 connectionLabel
connectionLabel=text("",12,MUTED);single(connectionLabel);
// 386 行 status
status=text("",12,MUTED);status.setMaxLines(2);status.setEllipsize(TextUtils.TruncateAt.END);
// 500 行 status
status.setMaxLines(2);status.setEllipsize(TextUtils.TruncateAt.END);
// 539 行 metadata detail
detail.setMaxLines(3);detail.setEllipsize(TextUtils.TruncateAt.MIDDLE);
```

路径类文字应该用 `MIDDLE` 而不是 `END`（保留盘符/根目录和文件名两头）。`MainActivity.java:953` 已经用了 `MIDDLE`（`target.setEllipsize(TextUtils.TruncateAt.MIDDLE)`），说明作者知道这个做法，只是没推广。

### M5. 三处视图创建后从未加入视图树 / 从未显示（死代码）

**`chatContext`（`MainActivity.java:66` 声明，`206` 置 null，`518` 读取）从未被赋值、从未 `addView`。**

```java
66:  private LinearLayout chatContext,chatHeader,attachmentRow;
206: ...emptyState=null;chatContext=null;composeFoot=null;...
518: if(chatContext!=null)chatContext.setVisibility(keyboardShown?View.GONE:View.VISIBLE);
```

`rg` 全文件只有这 3 处。`chatContext` 永远是 `null`，`518` 行的分支永远不执行。

**`composeFoot`（`MainActivity.java:73` 声明，`206` 置 null，`518` 读取）同样从未被赋值。**

```java
73:  private TextView composeFoot;
206: ...chatContext=null;composeFoot=null;...
518: if(composeFoot!=null)composeFoot.setVisibility(keyboardShown?View.GONE:View.VISIBLE);
```

同样的死分支。

**`effortLabel`（`MainActivity.java:47` 声明，`505` 赋值，`632` 设文本）从未 `addView`。**

```java
505: effortLabel=text("",11,MUTED);
632: effortLabel.setText(page.effort.isEmpty()||page.effort.equals("null")?"思考":page.effort);
```

`505` 行创建后**没有加入任何父容器**。对比同一行的 `modelLabel=option(...)` 和 `pluginLabel=option(...)`，两者都在 `506` 行被计算进 `options`（`for(TextView option:new TextView[]{modelLabel,access,pluginLabel})`）。`effortLabel` 不在这个数组里，也不在别处。它的文本在 `632` 行被认真计算并写入——**每次渲染都白算一遍**，且用户永远看不到"思考强度"。

**`stopButton` 是 `sendButton` 的别名，无独立用途。**

```java
83:  private Button sendButton,stopButton;
507: stopButton=sendButton;
```

`stopButton` 只被赋值和置 null（`205`），从不被读取使用。`updateSend()`（`515`）全部操作的是 `sendButton`。这是一个残留别名。

为什么是问题：`effortLabel` 是**功能缺失**——`renderSession()` 花了 4 次条件判断计算思考强度文案，代码里看起来像已实现，实际不可见。`chatContext`/`composeFoot` 是**空守卫**，`518` 行的两个 `if` 是纯粹的死分支，掩盖了"原本想做一个随键盘收起的上下文条"的未完成意图。

改法：
1. 若要显示 `effortLabel`，把它加进 `506` 行的数组：`new TextView[]{modelLabel,access,pluginLabel,effortLabel}` —— 注意此时四个选项都是 `weight` 分配，需要调整权重表达式（现在是 `option==modelLabel?2:1`）。
2. 若不要，删除 `47` 行的字段、`505` 行的赋值、`632` 行的 `setText`。
3. 删除 `chatContext`、`composeFoot` 字段及 `518` 行对应分支。
4. 删除 `stopButton` 字段及 `205`、`507` 行的赋值。

### M6. `refreshButton` 创建后未加入视图树（分支内悬空）

`MainActivity.java:377`：

```java
refreshButton=ui.iconButton("refresh","刷新会话");
Button create=ui.iconButton("plus","新建会话");
create.setOnClickListener(v->newSession());
add(heading,top,52);
top.setContentDescription(providerName(key)+" 客户端状态");
top.setOnClickListener(v->toggleApplicationMenu(top,key));
LinearLayout localActions=row();
localActions.addView(refreshButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
localActions.addView(create,new LinearLayout.LayoutParams(dp(48),dp(48)));
refreshButton.setOnClickListener(v->loadSessions(key,true));
```

`refreshButton` 加进了 `localActions`，`localActions` 在 `383` 行被加入 `heading`：

```java
383: localActions.addView(searchBox,0,new LinearLayout.LayoutParams(0,dp(44),1));add(heading,localActions,48);
```

所以 `refreshButton` **确实在视图树里**，但排在 `searchBox` 之后。`top.setContentDescription` + `top.setOnClickListener`（`377`）让整个头部行可点，同时 `refreshButton`/`create` 又各自可点——**点击区域重叠**：用户点搜索框右侧空白会触发 `toggleApplicationMenu`（弹应用菜单），而不是预期行为。

而且 `localActions.addView(searchBox, 0, lp)` 的 index=0 把搜索框**插到 `refreshButton` 和 `create` 之前**，所以视觉顺序是 `[搜索框][刷新][新建]`，但代码书写顺序是先加 `refresh`/`create` 再加 `searchBox`。这种"先加两个再加 index=0"的写法极易读错。

改法：明确顺序并消除重叠的点击语义。把 `searchBox` 先加到 `localActions`，再 `addView(refreshButton)`/`addView(create)`；`top.setOnClickListener` 的锚点改为只覆盖标题文字（把 `top` 的点击移到 `providerTitle` 上），避免和右侧按钮抢触摸。

### M7. `NativeUi.button()` 的禁用态只有文字色变化，背景完全不变

`NativeUi.java:112`：

```java
b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,primary?onPrimary():INK}));
b.setBackground(touch(primary?GREEN:SOFT,14,false));
```

`ColorStateList` 只管文字色。`setEnabled(false)` 时背景仍是满色 `GREEN` 或 `SOFT`。

调用点里大量依赖禁用态表达"当前不可用"：

- `MainActivity.java:332,333,335,336,337` — `requery/retry/withdraw/failed/checked` 全部 `setEnabled(!sending)`，但 `sending` 时五个按钮看起来完全正常（只有文字变灰 `MUTED`），用户会反复点。
- `MainActivity.java:653` — 队列操作 `action.setEnabled(!sending&&pending==null&&...)`，其中 `action.setBackground(ui.touch(Color.TRANSPARENT,10,false))` 是**透明背景**（覆盖了 `button()` 的默认背景），所以连文字色变化都被 `ui.touch` 的 ripple 盖过，禁用态几乎不可见。
- `MainActivity.java:783` — `control.setEnabled(...)` 配了 `control.setAlpha(action.equals("start")||running?1f:.4f)`，**这个是做对了的**，说明作者知道该补 alpha。
- `MainActivity.java:918` — `b.setEnabled(!sending&&pending==null)`，无 alpha，同样问题。
- `MainActivity.java:318`（`ConnectionProfiles`相关）、`GitPage.java:38` 的 `if(!working)action.run()` 用逻辑拦截而非视觉禁用，用户看到的是可点但无反应。

**做得对的地方**：`MainActivity.java:405` `refreshButton.setEnabled(!cache.loading);refreshButton.setAlpha(cache.loading?.4f:1f);`；`MainActivity.java:596` `earlierButton.setEnabled(false)`；`MainActivity.java:783` 的 `setAlpha`。

改法：把 alpha 收进 `NativeUi.button()`，让禁用态默认可见：

```java
b.setBackground(touch(primary?GREEN:SOFT,14,false));
b.setAlpha(1f);
b.setEnabled(true);
// 覆盖 setEnabled 以同步 alpha
```

更干净的做法是给背景也用 `ColorStateList`/`StateListDrawable`，或者在 `NativeUi` 提供一个 `void enabled(Button b,boolean on)` 辅助方法，把 `setEnabled` + `setAlpha` 绑在一起，替换掉散落的 ~13 处 `setEnabled`。

### M8. `MainActivity.java:505` 三个选项与 `506` 的权重/字号不一致

```java
505: modelLabel=option("模型 · 思考",()->toggleCatalog("model"));effortLabel=text("",11,MUTED);
     pluginLabel=option(cache.plugins.isEmpty()?"插件":"插件 · "+cache.plugins.size(),()->toggleCatalog("plugins"));
     TextView access=option("审批",()->chooseAccess());accessLabel=access;
506: for(TextView option:new TextView[]{modelLabel,access,pluginLabel}){option.setPadding(dp(2),0,dp(2),0);
     option.setTextSize(11);options.addView(option,new LinearLayout.LayoutParams(0,dp(44),option==modelLabel?2:1));}
```

`option()` 内部已经 `text(label,12,INK)`（`850`），到 `506` 又被覆盖成 `11`。同时 `850` 里设的 `v.setMaxWidth(dp(185))` 与 `506` 的 `LayoutParams(0, dp(44), weight)` 冲突——`weight` 模式下宽度由父容器分配，`setMaxWidth` 会**截断分配到的宽度**，三个选项按 `2:1:1` 分 360dp 宽度后，`modelLabel` 拿到约 167dp（< 185，不触发），`access`/`pluginLabel` 各拿到约 83dp，`setMaxWidth(185)` 不生效但也没意义。

`option()` 已经设 `setTextSize(12)`，`506` 改成 11 —— 这里存在**同一控件被设置两次字号**。

改法：`option()` 接受字号参数，或者从 `506` 移除 `setTextSize(11)` 让 `option()` 的 12 生效；`setMaxWidth(dp(185))` 在 weight 布局下删除。

---

## 轻微

### L1. `NativeUi.icon()` 已正确设 `IMPORTANT_FOR_ACCESSIBILITY_NO`，但手工 `new ImageView` 的地方没跟上

统计：全目录 `setImportantForAccessibility|IMPORTANT_FOR_ACCESSIBILITY` 仅 **4 处**：
- `MainActivity.java:568` — `chatHeader` 收起时
- `NativeUi.java:125` — `icon()` 内
- `FastScroller.java:16` — 设为 `YES`
- `StatusOverlay.java:214` — 弹层宠物头像

但手工创建的 `ImageView` 有 **8 处**（`new ImageView`：`MainActivity.java` 3 处、`MessageContent.java` 1 处、`StatusOverlay.java` 2 处、`NativeUi.java` 1 处、`NativePreview.java` 1 处）。未处理的：

- `MainActivity.java:125` `logo()` 返回的 `ImageView` —— 无 `setContentDescription`，无 `setImportantForAccessibility`。`brandBar()`（`141`）把它加进 `appBar`。TalkBack 会读成无标签图形，但它其实是装饰性的品牌图，应该设为 `NO`。
- `MainActivity.java:733` `avatar` —— **有** `card.setContentDescription(PetSprite.label(form)+"宠物")`（`732`）在父上，但 `avatar` 自己是空的 `ImageView`，会被 TalkBack 单独聚焦。应设 `IMPORTANT_FOR_ACCESSIBILITY_NO`。
- `MainActivity.java:980` `thumbnail` —— 无 `setContentDescription`（在 `item` 上），且 `item` 是可点的（见 S6）。
- `NativePreview.java:35` `image`（PDF 页面）—— 无 `setContentDescription`。TalkBack 读"图片"但不知道是第几页。`number` TextView 里有 `(index[0]+1)+" / "+count`，应该合并或在 `image` 上设描述。

`MessageContent.java:59` 的 `image.setContentDescription("图片预览")` 和 `StatusOverlay.java:214` 的 `glyph.setImportantForAccessibility(NO)` 是**做对的示范**，其余地方没推广。

### L2. 无文字的可点 `Button` 缺少 `setContentDescription`

`NativeUi.iconButton()`（`116`）**已经**正确调用了 `b.setContentDescription(description)`（`120`）。但以下无文字 `Button` 绕过 `iconButton()` 或未传描述：

- `MainActivity.java:503` — `sendButton=button("",true)` 创建时无描述，靠 `updateSend()`（`515`）里 `sendButton.setContentDescription(stop?"停止执行":...)` 补上。**但** `updateSend()` 在 `515` 早期有 `ChatCache current=...` 用了 `sendButton.setBackground(new ContextRing(...))` 覆盖背景 —— 若 `updateSend()` 因 `sendButton==null` 提前返回（`515` 首行），发送按钮会没有描述。
- `MainActivity.java:649` — `title=button(...)` 有文字，无描述，可接受。
- `GitPage.java:38` — `command()` 里 `ui.button(title,primary)` 有文字，可接受。
- `GitPage.java:29` — `Button branch=ui.button("分支",false)` 有文字。
- `StatusOverlay.java:216` — `close` 是 `ui.iconButton("close","关闭消息详情")`，**有**描述，正确。

实际缺描述的是 `MainActivity.java:125` 的 `logo()` 和 `NativePreview.java:35` 的 PDF `image`。

### L3. `FloatingNavigation.java:33–37` 的 `dp()` 返回 `float` 却用于 `Math.min(int,float)`

```java
37: setX(Math.max(0,Math.min(Math.max(0,spanX-dp(28)),pref.getFloat("floatX",.95f)*spanX)));
38: private float dp(float value){return value*getResources().getDisplayMetrics().density;}
```

`spanX` 是 `int`（`36`），`dp(28)` 返回 `float`。`Math.min(int,float)` 会提升为 `float`，`setX(float)` 接受，能编译。但 `Math.max(0,spanX-dp(28))` 里 `0` 是 `int`、表达式是 `float`，结果是 `float`，也没有丢失。**这是能工作的**，只是类型混用（其余所有类的 `dp()` 都返回 `int`：`MainActivity.java:119`、`NativeUi.java:95` 都是 `int`）。

为什么算轻微：`FloatingNavigation.dp()` 返回 `float` 而全工程其他 `dp()` 返回 `int`，是 API 不一致。`37` 行的 `Math.max(0, Math.min(...))` 若有人把 `dp()` 改成 `int`，`spanX-dp(28)` 仍是 int，行为不变——说明当前代码对类型不敏感，是巧合的安全。

改法：`private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}` 与其余保持一致。

### L4. `ContextRing.java:19` 的 `radius` 计算在极小圆环下退化

```java
19: RectF arc=new RectF(cx-radius+2*density,cy-radius+2*density,cx+radius-2*density,cy+radius-2*density);
```

当 `radius <= 2*density` 时，左边界 `cx-radius+2*density >= cx+radius-2*density` = 右边界，`RectF` 宽度变 `0` 或负。`sendButton` 是 `dp(56)`（`MainActivity.java:503`），`density` 在 xxhdpi 是 3，`2*3=6px`，`radius` 约 28px，不触发。但在低密度设备（`density=1`）上 `radius=28`（若 `sendButton` 尺寸相同），仍然安全。**这一条我不确定是否真会出问题**——需要知道 `radius` 从哪里传入才能判断。`ContextRing` 的构造签名在 `MainActivity.java:515` 是 `new ContextRing(getResources().getDisplayMetrics().density, usage)`，第一个参数是 `density` 而非 `radius`，我在 `ContextRing.java` 全文只看到 21 行，未能读到 `radius` 的定义。**标注为待确认，不作为结论。**

---

## 汇总计数

| 项目 | 统计 |
|---|---|
| `setTextSize` 总处数 | 22 |
| 不同字号值 | 9（9/11/12/14/15/16/17/34 + 参数透传） |
| `setPadding` 总处数 | 85 |
| `dp()` 数值种类（`MainActivity`） | 37 种 |
| `setContentDescription` 总处数 | 29 |
| `new ImageView` 手工创建 | 8 |
| `setImportantForAccessibility` 总处数 | 4 |
| `0xff` 字面量（排除 `NativeUi`/`PetSprite`） | `StatusOverlay` 8、`ContextRing` 1、`FlowBorderDrawable` 1、`NativePreview` 1（内联 HTML）、`MessageContent` 0 |
| `View.GONE`/`INVISIBLE` 出现 | 28 |
| `setOnClickListener`/`LongClick` | 82 |
| `dp(44)` / `dp(48)` | 14 / 14 |
| 代码中的死字段 | `chatContext`、`composeFoot`、`effortLabel`（未 addView）、`stopButton` |

## 建议的修复顺序

1. **S1 / S3** —— 把 `StatusOverlay.colors()` 与 `FlowBorderDrawable`/`ContextRing` 并入 `NativeUi.theme()`，消除双份主题维护。这是唯一会导致"换主题后 UI 不一致"的结构问题。
2. **S5 / S6** —— 48dp 触摸目标统一 + 附件删除点击区域修正。无障碍与误删风险。
3. **M5 / M6** —— 删除 4 个死字段，或把 `effortLabel` 真正显示出来。这是一处**已实现但用户看不到的功能**。
4. **M1 / M2 / M7** —— 把字号、间距、禁用态 alpha 收敛成 `NativeUi` 常量与辅助方法。
5. **M3 / M4 / L1** —— 分隔线走 `rule()`、补 `MaxLines`/`Ellipsize`、补无障碍属性。
6. **S2 / S4 / L3 / L4** —— 字面量换成常量、`getChildCount()-1` 改成显式索引、`dp()` 返回类型统一。
