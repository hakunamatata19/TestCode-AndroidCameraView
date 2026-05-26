package com.chen.androidcameraview

import androidx.annotation.DrawableRes

/**
 * RecyclerView 列表项数据模型
 * @param title 标题
 * @param subtitle 副标题
 * @param iconRes 图标资源
 * @param iconBgRes 图标背景资源（圆形）
 * @param activityClass 点击后跳转的 Activity 类
 */
data class MainItem(
    val title: String,
    val subtitle: String,
    @DrawableRes val iconRes: Int,
    @DrawableRes val iconBgRes: Int,
    val activityClass: Class<*>
)
