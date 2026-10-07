package io.passkeyfix.gmsfix;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * 让「改包版」App（Telegram 的 Nagram / NagramX / Forkgram / Nekogram 等）也能使用
 * Google 密码管理器的通行密钥。
 *
 * 作者说明：作者没有安卓开发经验，本模块 100% 由 AI（vibe coding）编写，仅在一台
 * 小米 17 / 澎湃 OS 4.0.0.32（Android 17, SDK 37）/ GMS 26.36.35 / LSPosed IT v2.2.0 上实测通过；
 * 仅为自用，只在自己设备失效时更新。详见 README。
 *
 * 背景：这类 App 为了能被 Telegram 服务器接受，会断言 origin=https://telegram.org，
 * 而 Google 只允许它白名单内的浏览器这么做，于是 GMS 在弹出任何界面之前就抛
 *   IllegalStateException("Origin is not being returned as the calling app did not match
 *   the privileged allowlist")
 * 客户端往往显示「无通行密钥应用 / 此设备上不可用」，通行密钥完全用不了。
 *
 * 做法（两层，互为兜底）：
 *   ① 按当前 GMS 版本的混淆名预装绕过；
 *   ② 挂 IllegalStateException(String) 探针，用异常自身的调用栈动态定位（跨版本）。
 * 绕过 = 该校验方法抛异常时，改为返回对象里保存的 origin。
 *
 * 注意：本模块在 GMS 进程内运行，作用域应为「Google Play 服务」。
 */
public class Module extends XposedModule {

    private static final String TAG = "PasskeyFix";
    private static final int INFO = 4;

    private static final String GMS = "com.google.android.gms";
    private static final String ALLOWLIST_MSG = "privileged allowlist";

    /** GMS 26.36.35 上做白名单校验的混淆类/方法；其它版本由探针兜底 */
    private static final String KNOWN_CLASS = "nft";
    private static final String KNOWN_METHOD = "b";

    private final Set<String> installed = new HashSet<>();

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            if (GMS.equals(param.getPackageName())) {
                hookGms(param.getClassLoader());
            }
        } catch (Throwable t) {
            log(INFO, TAG, "hook failed: " + t);
        }
    }

    private void hookGms(final ClassLoader cl) {
        log(INFO, TAG, "GMS process detected, installing bypass + probe");
        preInstall(cl, KNOWN_CLASS, KNOWN_METHOD);
        try {
            Constructor<?> ctor = IllegalStateException.class.getDeclaredConstructor(String.class);
            hook(ctor).intercept(chain -> {
                Object a0 = chain.getArgs().get(0);
                if (a0 instanceof String && ((String) a0).contains(ALLOWLIST_MSG)) {
                    locateAndBypass(cl);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(INFO, TAG, "probe install failed: " + t);
        }
    }

    private void preInstall(ClassLoader cl, String cls, String method) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            installBypass(c.getName() + "." + method, c.getDeclaredMethod(method, String.class));
        } catch (Throwable t) {
            log(INFO, TAG, "pre-install " + cls + "." + method + " skipped: " + t);
        }
    }

    private void installBypass(final String id, final Method m) {
        if (!installed.add(id)) {
            return;
        }
        try {
            hook(m).intercept(chain -> {
                try {
                    return chain.proceed();
                } catch (Throwable t) {
                    String origin = findOrigin(chain.getThisObject());
                    if (origin == null) {
                        log(INFO, TAG, "no origin field on " + id + ", keeping rejection");
                        throw t;
                    }
                    log(INFO, TAG, "bypassed " + id + " -> origin=" + origin);
                    return origin;
                }
            });
            log(INFO, TAG, "bypass installed on " + id);
        } catch (Throwable t) {
            log(INFO, TAG, "installBypass failed for " + id + ": " + t);
            installed.remove(id);
        }
    }

    /** 用异常自身的调用栈定位做校验的方法；只认能从目标 App 加载器里加载出来的类 */
    private void locateAndBypass(final ClassLoader cl) {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            final String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("android.")
                    || c.startsWith("io.github.libxposed.") || c.startsWith("io.passkeyfix.")) {
                continue;
            }
            try {
                Class<?> cls = Class.forName(c, false, cl);
                Method m = cls.getDeclaredMethod(e.getMethodName(), String.class);
                log(INFO, TAG, "allowlist check located: " + c + "." + e.getMethodName());
                installBypass(c + "." + e.getMethodName(), m);
                return;
            } catch (Throwable ignore) {
                // 不是目标 App 自己的类（例如框架/模块自身的混淆类），继续往下找
            }
        }
        log(INFO, TAG, "allowlist rejection seen but no usable frame found");
    }

    /** 被校验对象里保存的 origin（一个以 http 开头的 String 字段） */
    private static String findOrigin(Object obj) {
        if (obj == null) {
            return null;
        }
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof String && ((String) v).startsWith("http")) {
                        return (String) v;
                    }
                } catch (Throwable ignore) {
                    // 看下一个字段
                }
            }
        }
        return null;
    }
}
