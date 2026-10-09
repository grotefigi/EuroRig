# EuroRig Android design

EuroRig is a map-first, offline truck navigator. The map, destination search and
vehicle constraints take priority. This implementation uses existing Android
Views, system fonts and original vector drawables; it adds no UI runtime or font
download.

The UI UX Pro Max skill was installed from nextlevelbuilder/ui-ux-pro-max-skill
at commit `477bcb28c9812b385cb51a4605ddf30d7b2266e2`. Its Logistics/Delivery
search supports minimal, high-contrast controls. Generated marketing layouts
and editorial typography were rejected as inappropriate for driving. Its native
touch, theme, focus, text scaling and feedback guidance informs this design.
Hermes's measured scratch palette was reviewed; a dark-only release was rejected
because the user requires a working light/dark switch.

## Visual system

`AppPalette.java` is the runtime source for shared control and map colours.
Opaque cards use white in daytime and charcoal at night. Blue identifies the
available primary action. Secondary actions have visible boundaries; Stop uses
a distinct red fill and its explicit label. Restrictions retain road signs and
text in addition to colour.

Use system sans-serif, medium action labels and tabular numerals. Buttons retain
native semantics, ripple feedback, keyboard focus and disabled state. Interactive
labels expose button semantics. Structural icons are original vector paths from
one outline family; they are decorative beside text and named when standalone.

The spacing rhythm is 4/8/12/16 dp. Android controls have at least a 48 dp target.
Content respects system insets. Phone controls overlay a map; landscape uses a
scrollable rail. At large font sizes the vehicle and routing mode stack, text can
wrap, and map tools use named icons instead of clipped labels. Primary actions
use natural height instead of a fixed text container.

## Behaviour retained

Theme changes persist and preserve the camera. Guidance retains route tracking,
the heading arrow, pan/recenter, route progress removal, GPS-loss feedback and
Stop. The UI does not modify vehicle, access or ADR routing decisions. All country
download and import actions remain available; multi-country installation still
depends on the separate coherent-map implementation and validation.

## Verification

Run `tools/audit_design.py` for contrast and actual touch bounds across phone,
landscape and tablet sizes, including 200% system text and disabled animations.
Review the screenshots for wrapping, hierarchy and safe areas. Run the existing
theme, profile, exposed-UI and guidance audits, plus the native camera and route
display checks. Emulator GPS is an injection test, not real driving evidence.
