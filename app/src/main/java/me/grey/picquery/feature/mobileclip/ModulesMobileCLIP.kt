package me.grey.picquery.feature.mobileclip

import me.grey.picquery.feature.base.ImageEncoder
import me.grey.picquery.feature.base.TextEncoder
import org.koin.core.qualifier.named
import org.koin.dsl.module

val modulesMobileCLIP = module {
    single<PreprocessorMobileCLIP> { PreprocessorMobileCLIP() }
    single<TextEncoder> { TextEncoderMobileCLIP(get()) }
    single<ImageEncoder> {
        ImageEncoderMobileCLIP(
            context = get(),
            preprocessor = get<PreprocessorMobileCLIP>(),
            dispatcher = get(named("default"))
        )
    }
}