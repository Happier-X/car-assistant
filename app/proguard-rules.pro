# ==================== 混淆规则 ====================
# 数据模型（被 kotlinx.serialization 反射使用）
-keepclassmembers class com.carassistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.carassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.carassistant.**$$serializer { *; }

# 无障碍服务 & 广播接收器 & Service 由系统反射实例化
-keep class com.carassistant.AssistAccessibilityService { *; }
-keep class com.carassistant.TriggerReceiver { *; }
-keep class com.carassistant.AssistantService { *; }
-keep class com.carassistant.MainActivity { *; }

# 反射调用的 WifiManager 隐藏 API 不能被裁掉
-keep class android.net.wifi.WifiManager { *; }
-keep class android.net.wifi.WifiConfiguration { *; }

# kotlinx.serialization 内部
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**