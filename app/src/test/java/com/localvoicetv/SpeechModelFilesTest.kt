package com.localvoicetv

import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeechModelFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun validModelFilesPassBeforeNativeLoading() {
        temp.newFile("model.onnx").writeText("abc")
        SpeechModelFiles.checkValid(temp.root, mapOf("model.onnx" to abcHash))
    }

    @Test fun truncatedOrModifiedModelsAreRejectedBeforeNativeLoading() {
        temp.newFile("model.onnx").writeText("ab")
        assertThrows(IllegalStateException::class.java) {
            SpeechModelFiles.checkValid(temp.root, mapOf("model.onnx" to abcHash))
        }
    }

    @Test fun incompleteInstallIsRejectedBeforeNativeLoading() {
        assertThrows(java.io.FileNotFoundException::class.java) {
            SpeechModelFiles.checkValid(temp.root, mapOf("model.onnx" to abcHash))
        }
    }
}
