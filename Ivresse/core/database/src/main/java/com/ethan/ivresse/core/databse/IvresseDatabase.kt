package com.ethan.ivresse.core.databse

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [

    ],
    version = 1,
    autoMigrations = [

    ],
    exportSchema = true
)
abstract class IvresseDatabase : RoomDatabase() {

}