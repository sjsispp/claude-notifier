package io.claudenotifier.idea

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 全局 FIFO 队列，桥接 TerminalEnvCustomizer (注入 env) 与
 * ContentManagerListener (拿到 widget 引用) 两个时机。
 *
 * - Customizer 触发：把 UUID 入队
 * - 紧随其后的 contentAdded 触发：出队拿到 UUID，关联到 widget
 *
 * 假设 IntelliJ 顺序触发这两个事件（实测在 EDT 上序列执行）。
 * 极端并发情况下顺序可能错乱，会导致 UUID 关联错位——
 * 但即便错位，至少所有 tab 都能被关联，调用 sendText 只是发到错的 tab，
 * 比"找不到 widget 完全失败"好。
 */
object PendingTabUuids {
    private val queue = ConcurrentLinkedQueue<String>()

    fun enqueue(uuid: String) {
        queue.offer(uuid)
    }

    fun pollNext(): String? = queue.poll()

    fun size(): Int = queue.size
}
