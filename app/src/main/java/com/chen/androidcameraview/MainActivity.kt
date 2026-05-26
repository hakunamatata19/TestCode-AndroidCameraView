package com.chen.androidcameraview

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.chen.androidcameraview.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupRecyclerView()
    }

    private fun setupRecyclerView() {
        // 配置列表数据，可以手动添加更多项目
        val items = listOf(
            MainItem(
                title = "录像",
                subtitle = "录制带时间戳水印的视频",
                iconRes = android.R.drawable.ic_media_play,
                iconBgRes = R.drawable.bg_icon_video,
                activityClass = DetailActivity::class.java
            ),
            MainItem(
                title = "拍照",
                subtitle = "拍摄并烧录时间戳水印",
                iconRes = android.R.drawable.ic_menu_camera,
                iconBgRes = R.drawable.bg_icon_photo,
                activityClass = PhotoCaptureActivity::class.java
            ),
        )

        val adapter = MainAdapter(items) { item ->
            // 点击跳转到对应的 Activity
            val intent = Intent(this, item.activityClass)
            intent.putExtra(DetailActivity.EXTRA_TITLE, item.title)
            startActivity(intent)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
        }
    }
}