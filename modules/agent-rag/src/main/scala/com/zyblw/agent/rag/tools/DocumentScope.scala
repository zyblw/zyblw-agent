package com.zyblw.agent.rag.tools

import zio.json.*

/** 宿主明确声明的知识资料范围。
  *
  * 缺失、空白和无法解析都不是全库查询。全库只在宿主写入 `documentScope=unrestricted` 时成立；限定范围使用非空文档 ID。
  */
enum DocumentScope:
  case Unrestricted
  case Restricted(documentIds: Set[String])

object DocumentScope:
  val ModeAttribute: String     = "documentScope"
  val SingleAttribute: String   = "scopeDocumentId"
  val ManyAttribute: String     = "scopeDocumentIds"
  val UnrestrictedToken: String = "unrestricted"
  val RestrictedToken: String   = "restricted"

  def unrestrictedAttributes: Map[String, String] =
    Map(ModeAttribute -> UnrestrictedToken)

  def restrictedAttributes(documentIds: Set[String]): Map[String, String] =
    documentIds.toList.map(_.trim).filter(_.nonEmpty).distinct.sorted match
      case Nil         => Map(SingleAttribute -> "")
      case head :: Nil => Map(SingleAttribute -> head)
      case many        => Map(ManyAttribute -> many.toJson)

  /** 从可信 RunContext 属性解析范围。属性缺失与空白值都会失败。 */
  def parse(attributes: Map[String, String]): Either[String, DocumentScope] =
    val mode   = attributes.get(ModeAttribute)
    val single = attributes.get(SingleAttribute)
    val many   = attributes.get(ManyAttribute)
    if mode.exists(_.trim.isEmpty) then Left("documentScope 为空，拒绝放宽为全库")
    else if single.exists(_.trim.isEmpty) then Left("scopeDocumentId 为空，拒绝放宽为全库")
    else
      parseIds(single, many).flatMap { ids =>
        mode.map(_.trim) match
          case Some(UnrestrictedToken) if ids.nonEmpty || single.isDefined || many.isDefined =>
            Left("documentScope=unrestricted 不能同时限定文档")
          case Some(UnrestrictedToken) =>
            Right(DocumentScope.Unrestricted)
          case Some(RestrictedToken) if ids.nonEmpty =>
            Right(DocumentScope.Restricted(ids))
          case Some(RestrictedToken) =>
            Left("documentScope=restricted 必须提供非空文档")
          case Some(other) =>
            Left(s"无法识别的 documentScope: $other")
          case None if ids.nonEmpty =>
            Right(DocumentScope.Restricted(ids))
          case None =>
            Left("知识检索缺少明确的资料范围。宿主必须写入 documentScope=unrestricted，或写入非空文档 ID")
      }

  private def parseIds(single: Option[String], many: Option[String]): Either[String, Set[String]] =
    many match
      case Some(raw) if raw.trim.isEmpty =>
        Left("scopeDocumentIds 为空，拒绝放宽为全库")
      case Some(raw) =>
        raw.fromJson[List[String]].left.map(_ => "scopeDocumentIds 必须是 JSON 字符串数组").flatMap { values =>
          if values.isEmpty || values.exists(_.trim.isEmpty) then Left("scopeDocumentIds 含有空文档 ID")
          else Right((values.map(_.trim) ++ single.toList.map(_.trim)).toSet)
        }
      case None =>
        Right(single.map(_.trim).filter(_.nonEmpty).toSet)
