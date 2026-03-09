package com.chen.androidcameraview

/**
 * RecyclerView 列表项数据模型
 * @param title 标题
 * @param activityClass 点击后跳转的 Activity 类
 */
data class MainItem(
    val title: String,
    val activityClass: Class<*>
)


