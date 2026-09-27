# Pocket Agent R8 规则
#
# 当前 release 构建未开启混淆（见 app/build.gradle.kts 中 isMinifyEnabled），
# 本文件为将来开启 R8 时预留。

# 项目自带手写 JSON 解析（core/json/Json.kt），不依赖反射，无需保留规则。
# kotlinx-coroutines 自带 consumer rules，无需额外配置。
