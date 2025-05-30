package com.example.h2wallpaper // 确保包名正确

/**
 * 用作 LiveData 包装器的数据类，代表一个事件。
 * 主要用于确保事件（如 Toast 消息）只被消费一次。
 */
open class Event<out T>(private val content: T) {

    private var hasBeenHandled = false

    /**
     * 返回事件的内容，并且将其标记为已处理，防止后续再次消费。
     * 如果事件已被处理过，则返回 null。
     * @return 如果事件未被处理，则返回内容 T；否则返回 null。
     */
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    /**
     * 返回事件的内容，无论它是否已经被处理过。
     * 这个方法允许查看事件内容而不将其标记为已处理。
     * @return 事件的内容 T。
     */
    fun peekContent(): T = content
}