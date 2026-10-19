package com.poolaim.overlay

import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Tensor

/** Android-only runtime adapter; needs pytorch_android_lite 1.13.1 and a real .ptl file. */
class PytorchLiteAdapter(modelAbsolutePath: String) : PatchLogitModel, AutoCloseable {
    private val module = LiteModuleLoader.load(modelAbsolutePath)
    override fun logit(chwRgb: FloatArray): Float {
        require(chwRgb.size == 3 * 48 * 48)
        val input = Tensor.fromBlob(chwRgb, longArrayOf(1, 3, 48, 48))
        val output = module.forward(IValue.from(input)).toTensor().dataAsFloatArray
        require(output.size == 1) { "Expected one logit, got ${output.size}" }
        return output[0]
    }
    override fun close() { module.destroy() }
}
