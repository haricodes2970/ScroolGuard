package com.hari.scrollguard

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Detects Instagram Reels and YouTube Shorts via the accessibility node tree.
 * Other areas of those apps (DMs, stories, search) return false so touches scroll freely.
 */
object FeedDetector {

    fun isInScrollableFeed(packageName: String, root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return when (packageName) {
            "com.instagram.android" -> isInstagramReels(root)
            "com.google.android.youtube" -> isYouTubeShorts(root)
            else -> false
        }
    }

    private fun isInstagramReels(root: AccessibilityNodeInfo): Boolean {
        if (treeContains(root) { node ->
                val cls = node.className?.toString().orEmpty()
                cls.contains("ViewPager2") && nodeHasReelHint(node)
            }
        ) {
            return true
        }
        return treeContains(root) { node ->
            nodeTextOrDescription(node).contains("reel")
        }
    }

    private fun isYouTubeShorts(root: AccessibilityNodeInfo): Boolean {
        if (windowTitleContainsShorts(root)) return true
        return treeContains(root) { node ->
            node.className?.toString().orEmpty().contains("ShortsPlayerFragment", ignoreCase = true)
        }
    }

    private fun windowTitleContainsShorts(root: AccessibilityNodeInfo): Boolean {
        val window: AccessibilityWindowInfo? = root.window
        val title = window?.title?.toString()?.lowercase().orEmpty()
        return title.contains("shorts")
    }

    private fun nodeHasReelHint(node: AccessibilityNodeInfo): Boolean {
        val hint = nodeTextOrDescription(node)
        return hint.contains("reel")
    }

    private fun nodeTextOrDescription(node: AccessibilityNodeInfo): String {
        val desc = node.contentDescription?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        return "$desc $text".lowercase()
    }

    private fun treeContains(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (depth > 14) return false
        if (predicate(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (treeContains(child, depth + 1, predicate)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }
}
