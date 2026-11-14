# Changelog · 更新记录

## v0.1.3 · 2026-11-14

- 真机上灯拖不动的问题修好了：SDK 每个拖动事件只给这一步的位移，现在先累加再移动灯，灯跟着手走、松手停住。
- 选中的灯旁边多了 3D ▲▼◀▶ 键，点一下挪一格，按住连跳。
- 设置里加「怎么操作」页（每个动作写手和手柄两种做法）；第一次进来台面前有一句引导。
- 摄影棚和控制窗按启动时的眼高摆，坐着也够得着：摄影棚在右前 31°，控制窗在左前 31°（看向「看这里」牌才打开）。
- 滑条点一下、拖一下都能换档。
- 模拟器 QA 第 4 轮通过；三种拖灯、▲▼◀▶、左右布局坐着用的舒适度待真机确认。
- 已知问题：首次启动字条写「控制窗关掉了」但窗口没开过；L5 / L7 开局补光藏在主光后；横拖灯会顺带抬高。

- Lights follow the hand again on the headset: the SDK sends each drag event's own delta, which is now summed before moving the light.
- 3D ▲ ▼ ◀ ▶ keys beside the selected light (tap, or hold to repeat).
- Settings > How to use (hand and controller for every action) and a first-entry tip in front of the subject.
- Studio and window follow your eye height (seated or standing): studio 31° front-right, window 31° front-left (opens when you look at the "Look here" plate).
- Sliders follow taps and small drags, then settle on a step.
- Emulator QA round 4 passed; the three drag methods, the step keys and seated comfort still need an on-device check.
- Known issues: the first-launch strip says the window was closed although it never opened; in L5 / L7 the fill light starts behind the key light; a sideways drag also lifts the light.

## v0.1.2 · 2026-11-13

- 去掉应用自己画的手柄射线和落点，只用系统射线：每个手柄只有一条射线。拖灯时从主体连到灯球的细线是光线方向线，不是射线。
- 灯球挂系统悬停高亮（被指着 / 被看着时由系统画）。
- 模拟器 QA 第 3 轮通过；每个手柄一条射线、悬停高亮、三种拖法手感待真机确认。

- Removed the app-drawn controller ray and hit dot; only the system ray remains, one per controller. The thin line from the subject to a light while dragging is the light-direction line, not a ray.
- Light orbs use the system hover highlight.
- Emulator QA round 3 passed; one ray per controller, hover highlight and the three drag methods still need an on-device check.

## v0.1.1 · 2026-11-12

- 第一个发布版：8 道场面题 + 随机一题 + 自由打光；影子进框围栏变绿，[拍下] 给 0–3 星和一句为什么。
- 主光 / 补光 / 轮廓光三颗灯球：手直接捏、手柄射线、眼手捏合都能拖。
- Full Space：摄影棚在桌高、手够得着的地方，[叫回面前] 重摆。
- 按钮 64 dp；对准度卡不再裁星条；确认页整页卡片。

- First release: 8 lighting scenes + a random one + free lighting; the fence turns green when the shadow fits, [Capture] gives 0–3 stars and a one-line reason.
- Key / fill / rim light orbs can be dragged by direct pinch, controller ray or gaze + pinch.
- Full Space studio at table height within reach; [Recall] re-places it.
