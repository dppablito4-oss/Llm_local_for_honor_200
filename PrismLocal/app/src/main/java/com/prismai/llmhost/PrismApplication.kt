package com.prismai.llmhost

import android.app.Application
import androidx.work.Configuration

/** Supplies WorkManager's configuration when it is first requested. */
class PrismApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
