package com.leo.carunlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机广播兜底。
 *
 * 车机的 com.autostart(Auto Start 2.2)本身会拉起 App 的启动 Activity,
 * 这里再加一层保险:直接监听 BOOT_COMPLETED。
 *
 * 只负责拉起 MainActivity(它启动 Service 后立即结束),
 * 不走直接 startService —— 因为 Android 8+ 限制从广播启动后台服务。
 * 两条路径重复触发无害(接口幂等)。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "CarUnlock";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "收到开机广播: " + action);

        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        } catch (Throwable t) {
            Log.e(TAG, "拉起 MainActivity 失败: " + t);
        }
    }
}
