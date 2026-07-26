package com.zyblw.agent.multimodal

import com.zyblw.agent.core.*
import zio.*
import zio.stream.*

final case class BinaryAsset(bytes: Chunk[Byte], mediaType: String, metadata: Map[String, String] = Map.empty)
final case class ImageGenerationRequest(prompt: String, width: Int, height: Int, count: Int = 1)
final case class SpeechSynthesisRequest(text: String, voice: String, format: String)
final case class SpeechRecognitionRequest(audio: BinaryAsset, language: Option[String])

trait ImageModel:
  /** 根据提示词和生成参数返回一个或多个二进制图片资产。 */
  def generate(request: ImageGenerationRequest): IO[ModelError, Chunk[BinaryAsset]]

trait SpeechModel:
  /** 以背压字节流合成语音，消费者停止时应取消 Provider 请求。 */
  def synthesize(request: SpeechSynthesisRequest): ZStream[Any, ModelError, Byte]

  /** 把音频资产识别为文本。 */
  def transcribe(request: SpeechRecognitionRequest): IO[ModelError, String]

/** 多模态能力是外围 SPI；core 的 ContentPart 已保留引用类型，但不强迫所有 Provider 实现。 */
final case class MultimodalCapabilities(
    imageGeneration: Boolean,
    speechSynthesis: Boolean,
    transcription: Boolean
)
