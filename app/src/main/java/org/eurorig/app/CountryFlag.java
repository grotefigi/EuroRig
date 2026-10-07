package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Decorative country identity from the device font; action icons remain EuroRig vectors. */
final class CountryFlag extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path circle=new Path();
    private final String code;
    private final int fallback;
    CountryFlag(Context context,String code,int fallback){super(context);this.code=code;this.fallback=fallback;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    static String code(String id,String declared){
        if(declared.matches("[A-Z]{2}"))return declared;
        String pairs="albania:AL;andorra:AD;armenia:AM;austria:AT;belarus:BY;belgium:BE;bosnia-herzegovina:BA;bulgaria:BG;croatia:HR;cyprus:CY;czechia:CZ;czech-republic:CZ;denmark:DK;estonia:EE;faroe-islands:FO;finland:FI;france:FR;georgia:GE;germany:DE;gibraltar:GI;greece:GR;hungary:HU;iceland:IS;ireland:IE;italy:IT;kosovo:XK;latvia:LV;liechtenstein:LI;lithuania:LT;luxembourg:LU;malta:MT;moldova:MD;monaco:MC;montenegro:ME;netherlands:NL;north-macedonia:MK;norway:NO;poland:PL;portugal:PT;romania:RO;russia:RU;san-marino:SM;serbia:RS;slovakia:SK;slovenia:SI;spain:ES;sweden:SE;switzerland:CH;turkey:TR;ukraine:UA;united-kingdom:GB;vatican-city:VA";
        for(String pair:pairs.split(";"))if(id.equals(pair.substring(0,pair.indexOf(':'))))return pair.substring(pair.indexOf(':')+1);
        return "";
    }
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float size=Math.min(getWidth(),getHeight()),x=getWidth()/2f,y=getHeight()/2f;
        paint.setColor(fallback);canvas.drawCircle(x,y,size/2,paint);
        String glyph=code.length()==2?new String(Character.toChars(0x1f1e6+code.charAt(0)-'A'))+new String(Character.toChars(0x1f1e6+code.charAt(1)-'A')):"";
        paint.setTextSize(size*1.5f);boolean hasFlag=!glyph.isEmpty()&&paint.hasGlyph(glyph);
        if(!hasFlag){glyph=code.isEmpty()?"EU":code;paint.setTextSize(size*.35f);paint.setColor(Color.WHITE);}
        Paint.FontMetrics metrics=paint.getFontMetrics();circle.reset();circle.addCircle(x,y,size/2,Path.Direction.CW);
        canvas.save();canvas.clipPath(circle);canvas.drawText(glyph,x-paint.measureText(glyph)/2,y-(metrics.ascent+metrics.descent)/2,paint);canvas.restore();
    }
}
