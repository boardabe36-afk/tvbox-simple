package com.simple.tvbox.ui.history

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.simple.tvbox.R

class HistoryActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, HistoryFragment())
                .commitNow()
        }
    }
}
