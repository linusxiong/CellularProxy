@file:Suppress("FunctionName")

package com.cellularproxy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellularProxyApp() {
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("CellularProxy")
                    },
                )
            },
        ) { contentPadding ->
            CellularProxyAppContent(contentPadding)
        }
    }
}

@Composable
private fun CellularProxyAppContent(contentPadding: PaddingValues) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Operator console",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Dashboard, settings, Cloudflare, rotation, diagnostics, and logs will be wired here.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
