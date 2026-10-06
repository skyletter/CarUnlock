package com.leo.carunlock;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

/**
 * CarUnlock — 车机音源解锁
 *
 * 作用:调用车机音源仲裁服务,请求 APP 音源,解除功放静音。
 * 用于解决 AntennaPod / 喜马拉雅等第三方播放器启动播放无声的问题。
 *
 * 目标服务: fce_misc_service_source_ctrl
 * AIDL:     com.fce.misc.proxy.source.ISourceCtrl
 * 方法:     requestSource(String sourceId)  事务码 = 1
 * 有效值:   "app" / "navi" / "usb"
 *
 * 调用方式:反射 ServiceManager 拿 IBinder,直接 transact,不需要 AIDL 文件。
 * 若 Binder 路线被权限拦截,自动回退到 root 执行 service call。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CarUnlock";

    private static final String SVC_NAME = "fce_misc_service_source_ctrl";
    private static final String SVC_DESC = "com.fce.misc.proxy.source.ISourceCtrl";
    private static final int TRANSACTION_requestSource = 1;
    private static final String TARGET_SOURCE = "app";

    /** 首次等待:给系统服务启动留时间 */
    private static final long FIRST_DELAY_MS = 10000L;
    private static final long RETRY_DELAY_MS = 5000L;
    private static final int MAX_ATTEMPTS = 6;

    /** Activity 保持时间,保证解锁线程有时间跑完 */
    private static final long KEEP_ALIVE_MS = 45000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                unlock();
            }
        }, "carunlock");
        worker.setDaemon(true);
        worker.start();

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, KEEP_ALIVE_MS);
    }

    private void unlock() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            sleep(attempt == 1 ? FIRST_DELAY_MS : RETRY_DELAY_MS);

            IBinder binder = getServiceBinder();
            if (binder == null) {
                Log.w(TAG, "attempt " + attempt + ": 服务未就绪");
                continue;
            }

            int ret = callRequestSource(binder, TARGET_SOURCE);
            Log.i(TAG, "attempt " + attempt + ": requestSource(\"" + TARGET_SOURCE + "\") -> " + ret);

            if (ret == 1) {
                Log.i(TAG, "解锁成功(音源已切换到 " + TARGET_SOURCE + ")");
                return;
            }
            if (ret == -1) {
                Log.w(TAG, "Binder 调用异常,改用 root 兜底");
                break;
            }
            // ret == 0:可能是切换失败,也可能是幂等(音源已是 app),继续重试
        }

        rootFallback();
    }

    private IBinder getServiceBinder() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object obj = smClass.getMethod("getService", String.class).invoke(null, SVC_NAME);
            if (obj instanceof IBinder) {
                return (IBinder) obj;
            }
        } catch (Throwable t) {
            Log.e(TAG, "getService 失败: " + t);
        }
        return null;
    }

    /**
     * 等价于 AIDL:
     *   ISourceCtrl.Stub.asInterface(binder).requestSource("app")
     *
     * @return 1 成功 / 0 失败 / -1 异常
     */
    private int callRequestSource(IBinder binder, String sourceId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SVC_DESC);
            data.writeString(sourceId);
            boolean ok = binder.transact(TRANSACTION_requestSource, data, reply, 0);
            if (!ok) {
                return 0;
            }
            reply.readException();
            return reply.readInt() != 0 ? 1 : 0;
        } catch (Throwable t) {
            Log.e(TAG, "transact 失败: " + t);
            return -1;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void rootFallback() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "0", "service", "call", SVC_NAME, "1", "s16", TARGET_SOURCE
            });
            int code = p.waitFor();
            Log.i(TAG, "root 兜底 退出码=" + code);
        } catch (Throwable t) {
            Log.e(TAG, "root 兜底失败: " + t);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
