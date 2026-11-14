# Changelog · 更新记录

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
