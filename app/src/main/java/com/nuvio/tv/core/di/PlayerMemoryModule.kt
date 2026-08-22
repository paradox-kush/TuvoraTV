package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.PlayerMemoryBudget
import com.nuvio.tv.core.memory.AndroidPlayerMemoryBudget
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the neutral player memory budget to its device-tier implementation (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerMemoryModule {
    @Binds
    @Singleton
    abstract fun bindPlayerMemoryBudget(impl: AndroidPlayerMemoryBudget): PlayerMemoryBudget
}
