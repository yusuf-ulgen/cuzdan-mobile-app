package com.yusufulgen.cuzdan.di

import android.content.Context
import androidx.room.Room
import com.yusufulgen.cuzdan.data.local.AppDatabase
import com.yusufulgen.cuzdan.data.local.dao.AssetDao
import com.yusufulgen.cuzdan.data.local.dao.PortfolioDao
import com.yusufulgen.cuzdan.data.local.dao.MarketAssetDao
import com.yusufulgen.cuzdan.data.local.dao.PortfolioHistoryDao
import com.yusufulgen.cuzdan.data.local.dao.PriceAlertDao

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE assets ADD COLUMN currency TEXT NOT NULL DEFAULT 'TRY'")
        db.execSQL("ALTER TABLE market_assets ADD COLUMN currency TEXT NOT NULL DEFAULT 'TRY'")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE portfolios ADD COLUMN createdAt INTEGER NOT NULL DEFAULT " + System.currentTimeMillis())
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE portfolios ADD COLUMN depositedAmount TEXT NOT NULL DEFAULT '0'")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE assets ADD COLUMN buyCurrency TEXT NOT NULL DEFAULT 'TRY'")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE market_assets ADD COLUMN chartDataJson TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "cuzdan_db"
        )
        .addMigrations(MIGRATION_5_6, MIGRATION_8_9, MIGRATION_10_11, MIGRATION_13_14, MIGRATION_14_15)
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideAssetDao(database: AppDatabase): AssetDao {
        return database.assetDao()
    }

    @Provides
    fun providePortfolioDao(database: AppDatabase): PortfolioDao {
        return database.portfolioDao()
    }

    @Provides
    fun provideMarketAssetDao(database: AppDatabase): MarketAssetDao {
        return database.marketAssetDao()
    }

    @Provides
    fun providePortfolioHistoryDao(database: AppDatabase): PortfolioHistoryDao {
        return database.portfolioHistoryDao()
    }

    @Provides
    fun providePriceAlertDao(database: AppDatabase): PriceAlertDao {
        return database.priceAlertDao()
    }
}

