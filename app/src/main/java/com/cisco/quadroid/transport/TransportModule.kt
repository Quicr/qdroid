package com.cisco.quadroid.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    fun provideTrackCallbacks(): ConcurrentMap<String, MoqObjectCallback> {
        return ConcurrentHashMap<String, MoqObjectCallback>()
    }

    @Provides
    @Singleton
    fun provideMoqTransport(trackCallbacks: ConcurrentMap<String, MoqObjectCallback>): MoqTransport {
        return MoqNative(trackCallbacks)
    }
}
