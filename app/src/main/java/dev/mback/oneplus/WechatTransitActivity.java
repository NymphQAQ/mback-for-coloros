package dev.mback.oneplus;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

/** No window is drawn; an explicit user launch also works with ColorOS startup control. */
public final class WechatTransitActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (accepts(getIntent())) WechatTransit.open(getApplicationContext(),
                    getIntent().getParcelableExtra(WechatTransit.LAUNCH_EXTRA));
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    static boolean accepts(Intent intent) {
        if (intent == null || !WechatTransit.ACTION.equals(intent.getAction()) || intent.getData() != null) return false;
        try {
            if (intent.getExtras() == null || intent.getExtras().size() != 1) return false;
            PendingIntent target = intent.getParcelableExtra(WechatTransit.LAUNCH_EXTRA);
            return target != null && "com.android.systemui".equals(target.getCreatorPackage())
                    && (android.os.Build.VERSION.SDK_INT < 31 || target.isActivity());
        } catch (RuntimeException error) { return false; }
    }
}
