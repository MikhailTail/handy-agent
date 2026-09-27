# Handy Agent R8 规则
#
# 当前 release 构建未开启混淆（见 app/build.gradle.kts 中 isMinifyEnabled），
# 本文件为将来开启 R8 时预留。

# 开启混淆时，这些不能删：
# - Ktor CIO 引擎通过反射装配流水线
# - WebView 上的 @JavascriptInterface 方法（阶段 5 注入原生桥时才有）
