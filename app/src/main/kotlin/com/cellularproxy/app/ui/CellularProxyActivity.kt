package com.cellularproxy.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cellularproxy.app.R
import com.cellularproxy.app.service.CellularProxyForegroundService
import com.cellularproxy.app.service.ForegroundServiceActions

class CellularProxyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
    }

    private fun createContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val spacing = (12 * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)

            addView(
                TextView(context).apply {
                    text = getString(R.string.dashboard_title)
                    textSize = 28f
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.dashboard_summary)
                    textSize = 16f
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            addView(
                TextView(context).apply {
                    text = getString(R.string.dashboard_default_endpoint)
                    textSize = 14f
                    gravity = Gravity.CENTER_HORIZONTAL
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
            addView(
                Button(context).apply {
                    text = getString(R.string.dashboard_start_proxy)
                    setOnClickListener {
                        startForegroundService(commandIntent(ForegroundServiceActions.START_PROXY))
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing * 2),
            )
            addView(
                Button(context).apply {
                    text = getString(R.string.dashboard_stop_proxy)
                    setOnClickListener {
                        startService(commandIntent(ForegroundServiceActions.STOP_PROXY))
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).withTopMargin(spacing),
            )
        }
    }

    private fun commandIntent(action: String): Intent =
        Intent(this, CellularProxyForegroundService::class.java).setAction(action)
}

private fun LinearLayout.LayoutParams.withTopMargin(topMarginPx: Int): LinearLayout.LayoutParams =
    apply {
        topMargin = topMarginPx
    }
