package com.ethan.ivresse.initializers

import android.content.Context
import androidx.startup.Initializer
import androidx.work.Configuration
import androidx.work.WorkManager

class WorkManagerInitializer : Initializer<WorkManager> {
    override fun create(context: Context): WorkManager {
        val configuration = Configuration.Builder().build()
        WorkManager.initialize(context, configuration)
        return WorkManager.getInstance(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}