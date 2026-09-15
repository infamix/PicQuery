package me.grey.picquery.feature.mobileclip2

import me.grey.picquery.feature.base.ImageEncoder
import me.grey.picquery.feature.base.TextEncoder
import org.koin.dsl.module

val modulesMobileCLIP2 = module {
    single<TextEncoder> { TextEncoderMobileCLIPv2(get()) }
    single<PreprocessorMobileCLIPv2> { PreprocessorMobileCLIPv2() }
    // Single: TFLite Interpreter + GPU delegate are expensive to create and
    // hold native memory; creating them per-injection leaks.
    single<ImageEncoder> {
        ImageEncoderMobileCLIPv2(
            context = get(),
            preprocessor = get<PreprocessorMobileCLIPv2>()
        )
    }
}