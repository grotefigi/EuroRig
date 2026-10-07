# User-preferred implementation references

This catalog records the user's standing preferences for EuroRig. Read it when implementing or improving animation, map effects, vehicle movement, navigation chrome or AI features. The descriptions below identify the intended reference role; they do not certify current compatibility, maintenance, licensing or real-world truck routing correctness.

## Implementation order

1. Route drawing and vehicle animation: study trail-android, then HRCarMarkerAnimation or the Mindorks Uber sample.
2. Loading, status and icon effects: study Lottie and RichPath.
3. Bottom navigation and chrome: evaluate one of the Compose animated navigation references against the existing native Views architecture.
4. AI features: study NanoMaps, StrictNav and AI-Fuel-Assistant before choosing an implementation.

Use this preference order before inventing a new solution. Reuse existing helpers where they already provide the requested behavior. Review source, license, minimum SDK, dependency cost and lifecycle handling before adopting code or a dependency. Android 8 (API 26) remains the minimum supported platform. Do not migrate the app to a different map SDK merely to copy a visual effect.

## Route and vehicle animation

- [trail-android](https://github.com/amalChandran/trail-android): route animation reference for moving dots, dashes, sweep and draw/erase. Evaluate its native Canvas/Views implementation as well as map integrations; do not assume Google Maps is required for every module.
- [Uber-Car-Animation-Android](https://github.com/MindorksOpenSource/Uber-Car-Animation-Android): vehicle interpolation, path movement and dual-color route reference; adapt the movement pattern for EuroRig's original truck marker.
- [HRCarMarkerAnimation](https://github.com/TecOrb-Developers/HRCarMarkerAnimation): smooth position updates, turns and camera bearing reference.
- [UberUX](https://github.com/mohak1712/UberUX): transitions, map overlays and animated polyline/marker reference.
- [android-gd-smoothmarker](https://github.com/douruanliang/android-gd-smoothmarker): movement patterns for multiple vehicles if that feature becomes necessary.

## UI effects and navigation chrome

- [lottie-android](https://github.com/airbnb/lottie-android): loading, status, maneuver and truck animation reference.
- [RichPath](https://github.com/tarek360/RichPath): vector path animation and morphing reference.
- [AndroidAnimatedNavigationBar](https://github.com/exyte/AndroidAnimatedNavigationBar): Compose bottom navigation reference.
- [MorphNavBar](https://github.com/Melikash98/MorphNavBar): morphing bottom navigation reference.
- [curved-bottom-navigation](https://github.com/susonthapa/curved-bottom-navigation): curved navigation and AnimatedVectorDrawable reference.
- [LottieBottomNav](https://github.com/wwdablu/LottieBottomNav): navigation icons animated with Lottie.

## Truck and driver domain references

- [TruckNav-Sim](https://github.com/Rares-Muntean/TruckNav-Sim): simulator navigation ideas; simulator behavior is not evidence of real-road legality.
- [FancyNavi](https://github.com/aquawill/FancyNavi): truck feature checklist, including tolls, guidance and safety cameras; adapt ideas without requiring HERE for local routing.
- [Tachograph](https://github.com/velli20/tachograph): driver-hours and tachograph logic reference; verify applicable rules before implementing regulated calculations.

## AI references

- [NanoMaps](https://github.com/sunil-dhaka/NanoMaps): generated street-view reference.
- [StrictNav](https://github.com/Devansh-Maurya/StrictNav): missed-turn voice feedback reference.
- [AI-Fuel-Assistant](https://github.com/navrot73-gif/AI-Fuel-Assistant): fuel-aware suggestions and routing ideas.
- [NanoJev](https://github.com/TianyuCodings/NanoJev): future reference for ranking discrete choices such as parking suggestions or already-valid route alternatives. Its published game-trained Python/CUDA model is not an Android truck router or map-compression solution. Keep it outside the shipping app until local-device cost and useful navigation behavior are measured. It must never authorize a route rejected by the truck rules.

EuroRig must operate fully locally after country downloads. Cloud APIs, required subscriptions and proprietary map services do not satisfy that requirement. AI suggestions must never override vehicle dimensions, loaded weights, access restrictions or ADR checks. Do not invent restrictions or present generated scenery as verified road information.

## Navigation quality requirements

Keep animation inexpensive on lower-end devices. Cache marker assets, reuse drawing objects, avoid allocations in per-frame paths, respect the system animation setting and suspend animation when the view is inactive. Test route progress removal, actual location fixes, bearing changes, camera follow, manual panning, stale GPS, dark/light themes and lifecycle recovery. Decorative motion must preserve readable maneuvers, visible controls and accessible touch targets.

Keep reference assets and proprietary screenshots private. Ship original EuroRig assets or assets with reviewed redistribution licenses. Record adopted upstream versions and licenses in the project's dependency notices.
