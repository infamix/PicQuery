package me.grey.picquery.feature.clip

import me.grey.picquery.feature.base.ImageEncoder
import me.grey.picquery.feature.base.TextEncoder

import org.koin.core.qualifier.named
import org.koin.dsl.module

val modulesCLIP = module {
    single<PreprocessorCLIP> { PreprocessorCLIP() }
    single<TextEncoder> { TextEncoderCLIP(get()) }
    // Single: the encoder owns an ONNX session which is expensive to build.
    // Default dispatcher: encoding is CPU-bound, not I/O-bound.
    single<ImageEncoder> {
        ImageEncoderCLIP(
            context = get(),
            preprocessor = get<PreprocessorCLIP>(),
            dispatcher = get(named("default"))
        )
    }
}