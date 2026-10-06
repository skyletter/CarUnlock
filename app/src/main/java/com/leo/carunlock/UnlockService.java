package com.leo.carunlock;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

/**
 * 解锁服务 — 实际执行音源注册的工作线程。
 *
 * 放在 Service 里而不是 Activity 里,是为了避免 Theme.NoDisplay 的
 * Activity 生命周期限制(NoDisplay 的 Activity 必须在 onResume 前 finish())。
 *
 * 目标服务: fce_misc_service_source_ctrl
 * AIDL:     com.fce.misc.proxy.source.ISourceCtrl
 * 方法:     requestSource(String sourceId)  事务码 = 1
 * 有效值:   "app" / "navi" / "usb"
 */
public class UnlockService extends Service {

    private static final String TAG = "CarUnlock";

    private static final String SVC_NAME = "fce_misc_service_source_ctrl";
    private static final String SVC_DESC = "com.fce.misc.proxy.source.ISourceCtrl";
    private static final int TRANSACTION_requestSource = 1;
    private static final String TARGET_SOURCE = "app";

    /** 首次等待:给系统音频服务启动留时间 */
    private static final long FIRST_DELAY_MS = 8000L;
    private static final long RETRY_DELAY_MS = 5000L;
    private static final int MAX_ATTEMPTS = 6;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    unlock();
                } finally {
                    stopSelf();
                }
            }
        }, "carunlock-worker");
        worker.setDaemon(true);
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
                rootFallback();
                return;
            }
            // ret == 0:切换失败或幂等(音源已是 app),继续重试
        }

        Log.w(TAG, "Binder 路线未成功,尝试 root 兜底");
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
     * @return 1 成功 / 0 失败或幂等 / -1 异常
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
