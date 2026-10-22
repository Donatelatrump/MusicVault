package com.musicvault.app.ui

import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.WeakHashMap

/** Shared native-row semantics, rebuilt on bind so recycled rows never retain another song's actions. */
internal object NativeAccessibility {
    data class Action(val label: String, val invoke: () -> Unit)
    private val actionIds = WeakHashMap<View, List<Int>>()

    fun describe(
        view: View, description: String, state: String? = null,
        clickLabel: String? = null, checked: Boolean? = null,
        actions: List<Action> = emptyList(), decorative: List<View> = emptyList()
    ) {
        view.contentDescription = description
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.isFocusable = true
        ViewCompat.setScreenReaderFocusable(view, true)
        ViewCompat.setStateDescription(view, state)
        decorative.forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = if (checked != null) "android.widget.CheckBox" else "android.widget.Button"
                if (checked != null) { info.isCheckable = true; info.isChecked = checked }
                if (clickLabel == null) {
                    info.isClickable = false
                    info.removeAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
                }
            }
        })
        actionIds.remove(view)?.forEach { ViewCompat.removeAccessibilityAction(view, it) }
        if (clickLabel != null) {
            ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, clickLabel) { target, _ -> target.performClick() }
        } else ViewCompat.removeAccessibilityAction(view, AccessibilityNodeInfoCompat.ACTION_CLICK)
        actionIds[view] = actions.map { action -> ViewCompat.addAccessibilityAction(view, action.label) { _, _ -> action.invoke(); true } }
    }

    fun button(view: View, description: String) {
        view.minimumWidth = maxOf(view.minimumWidth, (48 * view.resources.displayMetrics.density).toInt())
        view.minimumHeight = maxOf(view.minimumHeight, (48 * view.resources.displayMetrics.density).toInt())
        describe(view, description, clickLabel = description)
    }

    fun heading(view: View) { ViewCompat.setAccessibilityHeading(view, true) }
}
