package com.ethan.ivresse.core.databse

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun providerIvresseDatabase(
        @ApplicationContext context: Context
    ): IvresseDatabase = Room.databaseBuilder(
        context,
        IvresseDatabase::class.java,
        "ivresse-database"
    ).build()
}