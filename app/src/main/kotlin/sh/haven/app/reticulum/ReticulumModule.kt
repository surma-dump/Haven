package sh.haven.app.reticulum

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.reticulum.ReticulumBridge
import sh.haven.core.reticulum.ReticulumTransport
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReticulumModule {

    @Binds
    @Singleton
    abstract fun bindReticulumBridge(impl: ChaquopyReticulumBridge): ReticulumBridge

    @Binds
    @Singleton
    @Named("chaquopy")
    abstract fun bindChaquopyTransport(impl: ChaquopyReticulumTransport): ReticulumTransport

    @Binds
    @Singleton
    @Named("native")
    abstract fun bindNativeTransport(impl: NativeReticulumTransport): ReticulumTransport

    companion object {
        /**
         * Provides the active ReticulumTransport. Defaults to Chaquopy
         * during the migration window. Switch to native once the Channel
         * interop is verified end-to-end on real devices.
         *
         * TODO: Read from DataStore developer setting for runtime toggle.
         */
        @Provides
        @Singleton
        fun provideActiveTransport(
            @Named("chaquopy") chaquopy: ReticulumTransport,
            @Named("native") native_: ReticulumTransport,
        ): ReticulumTransport {
            // Default to Chaquopy for now — flip to native_ when ready
            return chaquopy
        }
    }
}
