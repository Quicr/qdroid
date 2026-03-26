package com.cisco.quadroid.di

import android.content.Context
import com.cisco.quadroid.webrtc.WebRtcSessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebRtcModule {

    @Provides
    @Singleton
    fun provideWebRtcSessionManager(
        @ApplicationContext context: Context
    ): WebRtcSessionManager = WebRtcSessionManager(context)
}
