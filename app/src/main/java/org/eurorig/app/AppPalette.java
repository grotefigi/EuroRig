package org.eurorig.app;

import android.content.Context;
import android.content.res.Configuration;

/** Opaque, paired colours shared by Android controls and the local road renderer. */
final class AppPalette {
    final boolean dark;
    final int background,surface,secondary,text,muted,accent,onAccent,border,danger;
    final int land,casing,label,attribution;
    final int[] roads;

    AppPalette(Context context) {
        dark=context.getSharedPreferences("settings",0).getBoolean("dark_mode",
            (context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        background=dark?0xff020617:0xfff1f5f9;
        surface=dark?0xff191c22:0xffffffff;
        secondary=dark?0xff20242c:0xfff1f5f9;
        text=dark?0xfff8fafc:0xff0f172a;
        muted=dark?0xff9aa3b2:0xff475569;
        accent=dark?0xff4c9aff:0xff1d4ed8;
        onAccent=dark?0xff0b1220:0xffffffff;
        border=0xff64748b;
        danger=dark?0xffdc2626:0xffb91c1c;
        land=dark?0xff111b26:0xffe8eee8;
        casing=dark?0xff07101c:0xffbdcabe;
        label=dark?0xffdae4ec:0xff183745;
        attribution=dark?0xffaab8c7:0xff475569;
        roads=dark?new int[]{0xff8ca6b8,0xffff8787,0xfff2a94b,0xff344554}
            :new int[]{0xff5a707c,0xffb91c1c,0xff946317,0xff94a899};
    }
}
