package dev.mikhailtail.handyagent.core.mobile

/** 模型请求执行的一个动作（尚未解析成坐标）。 */
sealed interface MobileAction {
    /** 按序号点击。**必须**同时给出该序号所属的 dumpId，否则一律拒绝。 */
    data class TapIndex(val index: Int, val dumpId: String) : MobileAction

    /** 按坐标点击：树为空（游戏/画布）时的兜底，精度差、易失效。 */
    data class TapXY(val x: Int, val y: Int) : MobileAction

    data class Swipe(
        val dir: ScrollDir,
        val index: Int? = null,
        val distancePx: Int? = null,
        val dumpId: String? = null,
    ) : MobileAction

    data class Type(
        val text: String,
        val index: Int? = null,
        val submit: Boolean = false,
        val dumpId: String? = null,
    ) : MobileAction

    data class Key(val key: MobileKey) : MobileAction

    data class Launch(val packageName: String) : MobileAction
}

enum class ResolveError {
    /** 序号来自旧的 dump —— 界面可能已经变了，必须重新取树。 */
    STALE_TREE,
    /** 序号越界（树被截断，或模型自己编了一个）。 */
    OUT_OF_RANGE,
    /** 需要目标但没给（例如输入却没指定输入框）。 */
    NO_TARGET,
    /** 参数本身不合法。 */
    INVALID,
}

/** 解析结果：要么是可执行的坐标/键盘动作，要么是一个**明确的错误**。 */
sealed interface ResolvedAction {
    data class Tap(val point: PointI, val target: FlatNode?) : ResolvedAction
    data class Swipe(val path: List<PointI>, val target: FlatNode?) : ResolvedAction
    data class TypeText(val target: FlatNode?, val text: String, val submit: Boolean) : ResolvedAction
    data class KeyPress(val key: MobileKey) : ResolvedAction
    data class LaunchApp(val packageName: String) : ResolvedAction
    data class Failure(val reason: ResolveError, val message: String) : ResolvedAction
}

/**
 * 把模型的意图解析成可执行动作。
 *
 * 核心不变量：**宁可失败，也不猜**。
 * 序号对不上就返回 [ResolveError.STALE_TREE] 让模型重新取树，绝不"差不多点一个"——
 * 误触在手机上可能意味着转账、发消息、删文件。
 */
object ActionResolver {

    fun resolve(action: MobileAction, tree: FlatTree?): ResolvedAction = when (action) {
        is MobileAction.TapIndex -> resolveTapIndex(action, tree)
        is MobileAction.TapXY -> resolveTapXY(action, tree)
        is MobileAction.Swipe -> resolveSwipe(action, tree)
        is MobileAction.Type -> resolveType(action, tree)
        is MobileAction.Key -> ResolvedAction.KeyPress(action.key)
        is MobileAction.Launch -> resolveLaunch(action)
    }

    private fun resolveTapIndex(a: MobileAction.TapIndex, tree: FlatTree?): ResolvedAction {
        if (tree == null) {
            return ResolvedAction.Failure(ResolveError.STALE_TREE, "还没有屏幕快照，请先调用 mobile_ui_tree")
        }
        val target = lookup(a.index, a.dumpId, tree) ?: return staleOrRange(a.index, a.dumpId, tree)
        if (target.node.bounds.isEmpty) {
            return ResolvedAction.Failure(
                ResolveError.INVALID,
                "第 ${a.index} 项没有可见区域，无法点击",
            )
        }
        return ResolvedAction.Tap(GesturePlanner.tapPoint(target.bounds, tree.screen), target)
    }

    private fun resolveTapXY(a: MobileAction.TapXY, tree: FlatTree?): ResolvedAction {
        if (tree == null) {
            return ResolvedAction.Failure(ResolveError.STALE_TREE, "还没有屏幕快照，请先调用 mobile_ui_tree")
        }
        val s = tree.screen
        if (a.x !in 0 until s.width || a.y !in 0 until s.height) {
            return ResolvedAction.Failure(
                ResolveError.OUT_OF_RANGE,
                "坐标 (${a.x},${a.y}) 超出屏幕 ${s.width}x${s.height}",
            )
        }
        return ResolvedAction.Tap(PointI(a.x, a.y), null)
    }

    private fun resolveSwipe(a: MobileAction.Swipe, tree: FlatTree?): ResolvedAction {
        if (tree == null) {
            return ResolvedAction.Failure(ResolveError.STALE_TREE, "还没有屏幕快照，请先调用 mobile_ui_tree")
        }
        val target = when {
            a.index == null -> null
            else -> lookup(a.index, a.dumpId, tree) ?: return staleOrRange(a.index, a.dumpId, tree)
        }
        val area = target?.bounds?.takeIf { !it.isEmpty } ?: tree.screen.bounds
        return ResolvedAction.Swipe(
            path = GesturePlanner.swipeIn(area, a.dir, tree.screen, a.distancePx),
            target = target,
        )
    }

    private fun resolveType(a: MobileAction.Type, tree: FlatTree?): ResolvedAction {
        if (a.text.isEmpty()) {
            return ResolvedAction.Failure(ResolveError.INVALID, "要输入的文本为空")
        }
        val target = when {
            a.index == null -> null
            else -> lookup(a.index, a.dumpId, tree) ?: return staleOrRange(a.index, a.dumpId, tree)
        }
        // 密码框绝不允许自动填入（由执行侧结合 SensitiveScreenDetector 再兜一层）。
        if (target != null && target.node.password) {
            return ResolvedAction.Failure(
                ResolveError.INVALID,
                "第 ${a.index} 项是密码框，已拒绝自动输入",
            )
        }
        return ResolvedAction.TypeText(target, a.text, a.submit)
    }

    private fun resolveLaunch(a: MobileAction.Launch): ResolvedAction {
        val pkg = a.packageName.trim()
        if (pkg.isEmpty() || !pkg.contains('.') || pkg.contains(' ')) {
            return ResolvedAction.Failure(ResolveError.INVALID, "包名不合法：'${a.packageName}'")
        }
        return ResolvedAction.LaunchApp(pkg)
    }

    // ------------------------------------------------------------------ 查找

    /**
     * 按序号回查节点。dumpId 必须与当前树一致 —— 这是防"跨步骤用旧序号点错东西"的闸门。
     */
    private fun lookup(index: Int, dumpId: String?, tree: FlatTree?): FlatNode? {
        if (tree == null) return null
        if (dumpId != null && dumpId != tree.dumpId) return null
        return tree.byIndex(index)
    }

    private fun staleOrRange(index: Int, dumpId: String?, tree: FlatTree?): ResolvedAction {
        if (tree == null) {
            return ResolvedAction.Failure(ResolveError.STALE_TREE, "还没有屏幕快照，请先调用 mobile_ui_tree")
        }
        return if (dumpId != null && dumpId != tree.dumpId) {
            ResolvedAction.Failure(
                ResolveError.STALE_TREE,
                "序号来自旧的快照（dump=$dumpId，当前=${tree.dumpId}）：界面可能已变化，" +
                    "请重新调用 mobile_ui_tree 再操作",
            )
        } else {
            ResolvedAction.Failure(
                ResolveError.OUT_OF_RANGE,
                "序号 $index 不在当前快照里（共 ${tree.nodes.size} 项）",
            )
        }
    }
}
