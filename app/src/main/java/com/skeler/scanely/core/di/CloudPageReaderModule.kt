package com.skeler.scanely.core.di

import com.skeler.scanely.core.ai.BackendPageReader
import com.skeler.scanely.core.pdftools.pdf2office.CloudPageReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** PDF to Word reads its uncertain Arabic pages again through the Worker's OCR. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class CloudPageReaderModule {
    @Binds
    abstract fun bindCloudPageReader(reader: BackendPageReader): CloudPageReader
}
