# Half Measures

An Android slicing game in the spirit of Fruit Ninja: geometric shapes (triangles,
squares, pentagons, hexagons, and near-circles) launch up from the bottom of the
screen and you slice them with a swipe. The twist is precision, not speed — every
slice is scored by how close it comes to cutting the shape exactly in half by area.

## Rules

- Health starts at **100** and the run ends at **0**.
- Each cut is measured by the area split between its two pieces. The health lost
  equals the imbalance: a perfect 50/50 cut costs nothing, a 60/40 cut costs 20,
  a 90/10 cut costs 80, and so on.
- Score is awarded per cut too, weighted the same way, so precise cuts are worth
  more than sloppy ones. Chase the highest score before your health runs out.
- Shapes that fall off the bottom of the screen unsliced don't cost health — the
  only way to lose is by cutting badly, so a cautious player can always wait for
  a clean angle.

## How it's built

Plain Android View + Canvas, no game engine:

- `GameShape.kt` — a flying shape is a regular polygon (3–~28 sides) with
  position/velocity/rotation, launched on a projectile arc toward the upper
  screen so it's reachable mid-flight.
- `SliceMath.kt` — the actual slicing geometry. A swipe defines a line; convex
  polygons are split by that line via a standard two-sided clip
  (`splitPolygon`), and each half's area comes from the shoelace formula.
  `segmentSlicesShape` is the broad-phase check used to decide whether a given
  swipe segment actually grazes a shape, using distance-to-line plus a
  projection check so fast swipes with sparse touch samples still register.
- `GameView.kt` — the render/update loop (driven by `Choreographer`), touch
  handling that turns `ACTION_MOVE` history into slice segments, health/score
  bookkeeping, and all drawing (shapes, sliced halves flying apart, the swipe
  trail, HUD, and the ready/game-over overlays).
- `MainActivity.kt` — hosts `GameView` full-screen, immersive mode.

## Building

Open the project root in Android Studio (Giraffe or newer) and let Gradle sync —
it targets `compileSdk 34` / `minSdk 24` with AGP 8.5.2 and Kotlin 1.9.24, all
fetched from Google's and Maven Central's repositories. From the command line:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

Note: this project was authored and code-reviewed in a sandboxed environment
without access to Google's Maven repository (needed to download the Android
Gradle Plugin and SDK), so it could not be compiled or run there. It has not
been built or tested on-device — build it locally and give it a pass before
relying on it, and file/fix anything that comes up.
