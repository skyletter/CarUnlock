package com.leo.carunlock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 入口 Activity — 只负责把解锁请求转交给 UnlockService,然后立即结束。
 *
 * 使用 Theme.NoDisplay 的 Activity 必须在 onResume 完成前调用 finish(),
 * 否则系统会抛 IllegalStateException 并强杀进程。
 * 所以实际工作放在 Service 里做。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CarUnlock";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            startService(new Intent(this, UnlockService.class));
            Log.i(TAG, "已启动解锁服务");
        } catch (Throwable t) {
            Log.e(TAG, "启动解锁服务失败: " + t);
        }
        finish();
    }
}
